package humbleSHARK;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.jgit.api.BlameCommand;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.mongodb.morphia.Datastore;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import common.ConfigurationHandler;
import common.MongoAdapter;
import common.humble.HumbleConfigurationHandler;
import common.humble.HumbleParameter;
import de.ugoe.cs.smartshark.model.Commit;
import de.ugoe.cs.smartshark.model.File;
import de.ugoe.cs.smartshark.model.FileAction;
import de.ugoe.cs.smartshark.model.Hunk;
import de.ugoe.cs.smartshark.model.HunkBlameLine;
import de.ugoe.cs.smartshark.model.VCSSystem;

/**
 * @author Philip Makedonski
 */

public class HumbleApp {
	protected MongoAdapter adapter;
	protected Datastore targetstore;
	private HashMap<String, RevCommit> revisionCache = new HashMap<>();
	protected VCSSystem vcs;
	protected Repository repository;
	protected static Logger logger = (Logger) LoggerFactory.getLogger(HumbleApp.class.getCanonicalName());

	public static void main(String[] args) {
		//load configuration -> override parameters
		if (args.length == 1) {
			args = HumbleConfigurationHandler.getInstance().loadConfiguration("properties/sample");
		}
		
		HumbleParameter.getInstance().init(args);
		ConfigurationHandler.getInstance().setLogLevel(HumbleParameter.getInstance().getDebugLevel());
		
		HumbleApp app = new HumbleApp();
		
		if (HumbleParameter.getInstance().getCommit() == null) {
			app.processRepository();
		} else {
			app.processCommit();
		}
	}
	
	public HumbleApp() {
		init();
	}

	void init() {
		adapter = new MongoAdapter(HumbleParameter.getInstance());
		adapter.setPluginName("humbleSHARK");
		adapter.setRecordProgress(HumbleParameter.getInstance().isRecordProgress());
		targetstore = adapter.getTargetstore();
		adapter.setVcs(HumbleParameter.getInstance().getUrl());
		if (adapter.getVcs()==null) {
			logger.error("No VCS information found for "+HumbleParameter.getInstance().getUrl());
			System.exit(1);
		}

		try {
			repository = FileRepositoryBuilder.create(
				new java.io.File(HumbleParameter.getInstance().getRepoPath()+"/.git"));
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(1);
		}
	}

	public void processRepository() {
		List<Commit> commits = adapter.getCommits();
		int i = 0;
		int size = commits.size();
		for (Commit commit : commits) {
			i++;
			logger.info("Processing: "+i+"/"+size);
			processCommit(commit);
		}
	}
	
	public void processCommit() {
		processCommit(HumbleParameter.getInstance().getCommit());
	}

	public void processCommit(String hash) {
        processCommit(adapter.getCommit(hash));
	}
	
	public void processCommit(Commit commit) {
		adapter.resetProgess(commit.getId());
        int DETECTED_BLAMELINES = 0;
        int COMPRESSED_BLAMELINES = 0;
        int STORED_BLAMELINES = 0;

        //deal with merges
        //  -> skip altogether?
        //     hunks and actions are effectively duplicated for merged commits
        //  -> or hop over the previous commits for the correct blame lines?
        //     need to check on which of the previous commits the corresponding
        // 	   actions for the files from the merge commit took place
        //     may need multiple hops in case of multiple merges

        if (!HumbleParameter.getInstance().isProcessMerges() && commit.getParents().size()!=1) {
        	return;
        }
        
		//get parent
		//	-> hopefully this works for the blames 
		//	   otherwise last action needs to be resolved instead
        String parentRevision = getParentRevisionFromDB(commit.getRevisionHash());
        if (parentRevision == null) {
        	return;
        }

        //load actions
        List<FileAction> actions = adapter.getActions(commit);

        //load files
        logger.debug(commit.getRevisionHash().substring(0, 8) + " " + commit.getAuthorDate());
        for (FileAction a : actions) {
    		adapter.resetProgess(a.getId());
        	//check for special cases: mode A, R, D, etc.
    		if (a.getMode().equals("A")) {
        		//skip newly added
        		continue;
        	}
        	
        	//TODO: if rename action -> use old file name?
        	File file = adapter.getFile(a.getFileId());
            logger.info("  "+a.getMode()+"  "+file.getPath());

            if (file.getPath().endsWith(".pdf")) {
            	//skip pdfs for which blame information is incomplete
            	continue;
            }
            
            //load previous action?
            //try more accurate parent resolution
            //-> may require graph reconstruction
            //-> may be a good idea to store (not the same as parent)
            //  -> can parent work still?
            if (HumbleParameter.getInstance().isUseActionBasedParent()) {
            	parentRevision = getParentRevisionFromDB(commit.getRevisionHash(), file.getPath());
            }
            
            //load hunks
            List<Hunk> hunks = adapter.getHunks(a);
            
            //hunk interpolation (not saved)
            adapter.interpolateHunks(hunks);

            //get blame lines from git
            List<HunkBlameLine> blameLines = getBlameLines(hunks, parentRevision, file.getPath());
            DETECTED_BLAMELINES+=blameLines.size();

            //compress
            if (!HumbleParameter.getInstance().isSkipCompression()) {
            	blameLines = compressBlameLines(blameLines);
            	COMPRESSED_BLAMELINES+=blameLines.size();
            }
            
            //clear existing records
            for (Hunk h : hunks) {
            	targetstore.delete(targetstore.find(HunkBlameLine.class)
            		.field("hunk_id").equal(h.getId()));
            }
            
            //store blame lines
            for (HunkBlameLine hbl : blameLines) {
            	targetstore.save(hbl);
            	STORED_BLAMELINES++;
            }
            
    		adapter.logProgess(a.getId(), "action");
        }
        
        logger.info("Analyzed commit: " + commit.getRevisionHash());
		logger.info("  Found " + DETECTED_BLAMELINES + " blame lines.");
		logger.info("  Compressed " + COMPRESSED_BLAMELINES + " blame lines.");
		logger.info(("  Stored " + STORED_BLAMELINES + " blame lines."));

		adapter.logProgess(commit.getId(), "commit");
	}

	private String getParentRevisionFromDB(String hash) {
        //look up in db
		String parentRevision = null;
		Commit commit = adapter.getCommit(hash);
		if (commit.getParents().size()==1) {
			parentRevision = commit.getParents().get(0);
		} else if (commit.getParents().size()>1) {
    		//handle merge commits: skip over merged parent and take its parent
    		//check if this can be done more precisely with actions
    		String parentHash = commit.getParents().get(0);
    		logger.warn("  Multiple parents for "+hash+", proceeding with first parent "+parentHash);
    		return getParentRevisionFromDB(parentHash);
		} else {
			//no parent
		}
		return parentRevision;
	}
	
	private String getParentRevisionFromDB(String hash, String path) {
        //look up in db
		String parentRevision = null;
		Commit commit = adapter.getCommit(hash);
		if (commit.getParents().size()==1) {
			parentRevision = commit.getParents().get(0);
		} else if (commit.getParents().size()>1) {
    		//handle merge commits: skip over merged parent and take its parent
    		//check if this can be done more precisely with actions
    		String parentHash = commit.getParents().get(0);
    		logger.info("    Evaluating parent actions for "+path+" at "+hash);
    		for (String p : commit.getParents()) {
    			Commit parent = adapter.getCommit(p);
    	        //load actions
    	        List<FileAction> actions = adapter.getActions(parent);
    	        for (FileAction a : actions) {
    	        	//check for special cases: mode A, R, etc.
    	        	if (a.getMode().equals("A")) {
    	        		//skip newly added
    	        		continue;
    	        	}
    	            File file = adapter.getFile(a.getFileId());
    	            if (file.getPath().equals(path)) {
    	            	parentHash = p;
    	            	//effectively last matched
    	            	logger.info("      Proceeding with last matched parent "+parentHash);
    	            }
    	        }
    		}
    		
    		return getParentRevisionFromDB(parentHash, path);
		} else {
			//no parent
		}
		return parentRevision;
	}
	
	List<HunkBlameLine> getBlameLines(List<Hunk> hunks, String parentRevision, String path) {
        //calculate blames for previous commit/action
        //filter hunk-related lines only
        //-> default: line -> blamed commit
        //  -> will result in lots of entries
        //	-> most comfortable to work with later
        //calculate blame lines
        //-> target hunk/commit reference
        //  -> note hunk is on commit, blame is on previous action/parent
        //-> line number
        //-> source/blamed commit

		List<HunkBlameLine> blameLines = new ArrayList<>();
		try {
			//get blame for parent
			BlameResult blame = getBlameResult(parentRevision, path);
	
			//check if blame exists (should be missing on first commits only)
			if (blame == null) {
				return blameLines;
			}

			for (Hunk h : hunks) {
				blameLines.addAll(getBlameLines(h, blame));
			}
		} catch (Exception e) {
			logger.warn(e.getMessage());
			e.printStackTrace();
			if (e.getMessage()==null) {
				e.printStackTrace();
			}
		}
		//return objects
		return blameLines;
	}

	List<HunkBlameLine> getBlameLines(Hunk h, BlameResult blame) {
		List<HunkBlameLine> blameLines = new ArrayList<>();

		//-> split hunks with gaps (max 1 line gap permitted)?
        //	-> skip gap lines instead
        //  -> blame lines will not fully correspond to hunks 
		List<String> oldGappedLines = getOldGappedLines(h);
		
		for (int line = h.getOldStart(); line < h.getOldStart()+h.getOldLines(); line++) {
			//skip gap lines
			if (!oldGappedLines.get(line-h.getOldStart()).startsWith("-")) {
				continue;
			}
			
			//need to take gapLines into account? -> no
			RevCommit sourceCommit = blame.getSourceCommit(line-1);
			Commit blamed = adapter.getCommit(sourceCommit.getName());

			HunkBlameLine hbl = new HunkBlameLine();
			hbl.setHunkId(h.getId());
			hbl.setHunkLine(line);
			//getSourceLine is 0-based, so line-1
			//returned line value is also 0-based, so +1
			//need to take gapLines into account -> no
			hbl.setSourceLine(blame.getSourceLine(line-1)+1);
			hbl.setBlamedCommitId(blamed.getId());
			if (!HumbleParameter.getInstance().isSkipSourcePaths()) {
				hbl.setSourcePath(blame.getSourcePath(line-1));
			}
			blameLines.add(hbl);
		}
		return blameLines;
	}

	List<String> getOldGappedLines(Hunk h) {
		String[] lines = h.getContent().split("\n");
		//get old lines including gaps
		List<String> oldGappedLines = Arrays.stream(lines).filter(l -> !l.startsWith("+")).collect(Collectors.toList());
		return oldGappedLines;
	}

	HunkBlameLine compressContiguousBlameLines(List<HunkBlameLine> blameLines) {
		HunkBlameLine compressed = new HunkBlameLine();
		HunkBlameLine first = blameLines.get(0);
		compressed.setHunkId(first.getHunkId());
		compressed.setHunkLine(first.getHunkLine());
		compressed.setLineCount(blameLines.size());
		compressed.setSourceLine(first.getSourceLine());
		compressed.setBlamedCommitId(first.getBlamedCommitId());
		if (!HumbleParameter.getInstance().isSkipSourcePaths()) {
			compressed.setSourcePath(first.getSourcePath());
		}
		
		return compressed;
	}
	
	List<HunkBlameLine> compressBlameLines(List<HunkBlameLine> blameLines) {
		List<HunkBlameLine> compressedBlameLines = new ArrayList<>();
    	HunkBlameLine last = null;
		List<HunkBlameLine> toBeCompressed = new ArrayList<>();
		
        //-> start-count -> blamed commit
        //	-> if count is often 1 it makes no difference
        //  -> during testing reduced number of blame lines by ~70%
        //-> hunk -> blamed commits? (not implemented)
        //	-> lossy - especially at logical level impossible to determine blamed commits afterwards

		for (HunkBlameLine hbl : blameLines) {
    		if (last!=null
    			&& hbl.getHunkLine() == last.getHunkLine()+1 
    			&& hbl.getBlamedCommitId().equals(last.getBlamedCommitId())) {
    			if (hbl.getSourceLine() != last.getSourceLine()+1) {
    				//add
    				HunkBlameLine compressed = compressContiguousBlameLines(toBeCompressed);
    				compressedBlameLines.add(compressed);
    				toBeCompressed.clear();
    			}
    		} else {
    			if (last!=null) {
    				//add
    				HunkBlameLine compressed = compressContiguousBlameLines(toBeCompressed);
    				compressedBlameLines.add(compressed);
    				toBeCompressed.clear();
    			}
    		}
    		toBeCompressed.add(hbl);
    		last = hbl;
		}
		
		if (last!=null) {
			//compress last batch
			HunkBlameLine compressed = compressContiguousBlameLines(toBeCompressed);
			compressedBlameLines.add(compressed);
			toBeCompressed.clear();
		}
		
		return compressedBlameLines;
	}
	
	RevCommit getRevision(String hash) throws Exception {
		if (!revisionCache.containsKey(hash)) {
			try (RevWalk walk = new RevWalk(repository)) {
				RevCommit commit = walk.parseCommit(repository.resolve(hash));
				walk.close();
				revisionCache.put(hash, commit);
			} 
		}
		return revisionCache.get(hash);
    }
	
	BlameResult getBlameResult(String hash, String path) throws Exception {
		BlameCommand blamer = new BlameCommand(repository);
		blamer.setStartCommit(getRevision(hash));
		blamer.setFilePath(path);
		blamer.setFollowFileRenames(true);
		BlameResult blame = blamer.call();
		return blame;
	}
}
