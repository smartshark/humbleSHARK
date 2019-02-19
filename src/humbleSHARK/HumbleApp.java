package humbleSHARK;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.bson.types.ObjectId;
import org.eclipse.jgit.api.BlameCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.mongodb.morphia.Datastore;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import common.HunkSignatureHandler;
import common.MongoAdapter;
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
	protected HunkSignatureHandler hsh = new HunkSignatureHandler();
	protected static Logger logger = (Logger) LoggerFactory.getLogger(HumbleApp.class.getCanonicalName());

	public static void main(String[] args) {		
		HumbleParameter.getInstance().init(args);
		
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
		String name = HumbleParameter.getInstance().getUrl().substring(HumbleParameter.getInstance().getUrl().lastIndexOf("/")+1).replaceAll("\\.git", "");
		if (HumbleParameter.getInstance().isSeparateDatabase()) {
			targetstore = adapter.getTargetstore("localhost", 27017, "localSHARK-"+name);
		}
		adapter.setVcs(HumbleParameter.getInstance().getUrl());
		if (adapter.getVcs()==null) {
			logger.error("No VCS information found for "+HumbleParameter.getInstance().getUrl());
			System.exit(1);
		}

		try {
			repository = FileRepositoryBuilder.create(
				new java.io.File(HumbleParameter.getInstance().getRepoPath()+"/.git"));
			adapter.setRevisionHashes(getOrderedRevisionHashes());
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(1);
		}

		if (HumbleParameter.getInstance().isFollowCopies()) {
			if (HumbleParameter.getInstance().isCacheActions()) {
				adapter.constructCachedFileActionMap(name);
			} else {
				adapter.constructFileActionMap();
			}
		}
	}

	public void processRepository() {
		List<Commit> commits = adapter.getCommits();
		int i = 0;
		int size = commits.size();
		for (Commit commit : commits) {
			i++;
			logger.info("Processing: "+i+"/"+size+" "+commit.getRevisionHash());
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

        //deal with merges
        //  -> skip altogether?
        //     hunks and actions are effectively duplicated for merged commits
        //  -> or hop over the previous commits for the correct blame lines?
        //     need to check on which of the previous commits the corresponding
        // 	   actions for the files from the merge commit took place
        //     may need multiple hops in case of multiple merges

        if (commit.getParents() == null || (!HumbleParameter.getInstance().isProcessMerges() && commit.getParents().size()!=1)) {
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

            if (file.getPath().endsWith(".pdf")) {
            	//skip pdfs for which blame information is incomplete
            	continue;
            }

            processFileAction(a, parentRevision);
            
    		adapter.logProgess(a.getId(), "action");
        }
        
        logger.info("Analyzed commit: " + commit.getRevisionHash());

		adapter.logProgess(commit.getId(), "commit");
	}

	public void processFileAction(FileAction a) {
		Commit commit = adapter.getCommit(a.getCommitId());
        String parentRevision = getParentRevisionFromDB(commit.getRevisionHash());
        if (parentRevision == null) {
        	return;
        }
        processFileAction(a, parentRevision);
	}
	
	public void processFileAction(FileAction a, String parentRevision) {
    	File file = adapter.getFile(a.getFileId());
    	Commit commit = adapter.getCommit(a.getCommitId());

    	logger.info("  "+a.getMode()+"  "+file.getPath());

    	//load hunks
        List<Hunk> hunks = adapter.getHunks(a);
        
        //hunk interpolation (not saved)
        adapter.interpolateHunks(hunks);

        //load previous action?
        //try more accurate parent resolution
        //-> may require graph reconstruction
        //-> may be a good idea to store (not the same as parent)
        //  -> can parent work still?
        if (HumbleParameter.getInstance().isUseActionBasedParent()) {
        	parentRevision = getParentRevisionFromDB(commit.getRevisionHash(), file.getPath());
        }
        
        //get blame lines from git
        String path = file.getPath();
        //handle renames and copies
        if (a.getMode().equals("R") || a.getMode().equals("C")) {
        	File oldFile = adapter.getFile(a.getOldFileId());
        	path = oldFile.getPath();
        }
        
		List<HunkBlameLine> blameLines = getBlameLines(hunks, parentRevision, path);

		//check for file copies
		if (HumbleParameter.getInstance().isFollowCopies()) {
        	blameLines = trackAcrossCopies(a, blameLines);
        }
        
        //compress
        if (!HumbleParameter.getInstance().isSkipCompression()) {
        	blameLines = compressBlameLines(blameLines);
        }
        
        //clear existing records
        for (Hunk h : hunks) {
        	targetstore.delete(targetstore.find(HunkBlameLine.class)
        		.field("hunk_id").equal(h.getId()));
        }
        
        //store blame lines
        for (HunkBlameLine hbl : blameLines) {
        	targetstore.save(hbl);
        }
	}

	List<HunkBlameLine> trackAcrossCopies(FileAction a, List<HunkBlameLine> blameLines) {
		List<HunkBlameLine> trackedBlameLines = new ArrayList<>();
		
		List<FileAction> allFileActions = adapter.getActionsFollowRenames(a.getFileId());

		for (HunkBlameLine hbl : blameLines) {
			HunkBlameLine realhbl = trackAcrossCopies(hbl, allFileActions);
			if (realhbl != null) {
				trackedBlameLines.add(realhbl);
			}
		}
		
		return trackedBlameLines;
	}

	HunkBlameLine trackAcrossCopies(HunkBlameLine hbl, List<FileAction> allFileActions) {
		Commit blamedCommit = adapter.getCommit(hbl.getBlamedCommitId());
		Optional<FileAction> optional = allFileActions.stream().filter(x->x.getCommitId().equals(hbl.getBlamedCommitId())).findFirst();
		if (!optional.isPresent()) {
			//blames assigned to merged commits
			//or to commits of a renamed file that is not resolved (due to merge dynamics 
			logger.warn("Corresponding action in blamed commit cannot be resolved:"
			+" "+blamedCommit.getRevisionHash().substring(0, 8) 
			+" parents="+blamedCommit.getParents().size() 
			+" "+blamedCommit.getAuthorDate()
			+" "+hbl.getHunkLine()
			+"->"+hbl.getSourceLine()
			+" in "+hbl.getSourcePath()
			);
			//fallback
		} else {
			FileAction blamedAction = optional.get();
			
			//if copy or rename
			//required for renames in case ignore renames is activated, possibly other cases as well
			if (blamedAction.getMode().equals("C") || blamedAction.getMode().equals("R")) {
				
				List<Hunk> blamedActionHunks = adapter.getHunks(blamedAction);
				adapter.interpolateHunks(blamedActionHunks);
				//get hit lines
				LinkedHashMap<Integer, Hunk> linesPostMap = getLinesPostMap(blamedActionHunks);
				if (!linesPostMap.containsKey(hbl.getSourceLine())) {
					//not hit -> trace back
					if (allFileActions.indexOf(blamedAction) > 0) {
						String blamedActionParentPath = adapter.getFile(blamedAction.getOldFileId()).getPath();
						//TODO: parameterize?
						String blamedCommitParent = getParentRevisionFromDB(blamedCommit.getRevisionHash(), blamedActionParentPath);
						
						//get offset
						LinkedHashMap<Integer,Integer> hunksLineMap = hsh.getHunksLineMap(blamedActionHunks);
						
						Integer d = hunksLineMap.keySet().stream()
								.filter(l -> l <= hbl.getSourceLine())
								.map(l -> hunksLineMap.get(l))
								.reduce((f, s) -> s).orElse(0);
						//TODO: cache?
						BlameResult blamedActionParentBlame = null;
						try {
							//get blame for parent
							//use parent instead of previous action
							//can help with merge side effects in hunks causing off-by-N errors
							blamedActionParentBlame = getBlameResult(blamedCommitParent, blamedActionParentPath);
							int blamedActionParentLine = hbl.getSourceLine()-d;
							
							//check if blame exists (should be missing on first commits only)
							if (blamedActionParentBlame != null) {
								//check for anomalies in the target parent line
								if (blamedActionParentLine-1 > blamedActionParentBlame.getResultContents().size()) {
									logger.warn("FIX: " 
											+" "+blamedAction.getMode() 
											+" "+blamedCommit.getRevisionHash().substring(0,8)
											+" --parent--> "+blamedCommitParent.substring(0,8)
											+" "+blamedActionParentPath
											+" Line: "+hbl.getSourceLine()
											+" Difference: "+d
											+" Blame size: "+blamedActionParentBlame.getResultContents().size()
											);
									return hbl;
								} else {
									HunkBlameLine realhbl = getBlameLine(blamedActionParentBlame, blamedActionParentLine, hbl.getHunkId());
									//override with the original hunk line
									realhbl.setHunkLine(hbl.getHunkLine());
									//recursive check
									realhbl = trackAcrossCopies(realhbl, allFileActions);
									return realhbl;
								}
								
							} else {
								logger.warn("FIX: Blame result for parent could not be calculated"
										+" "+blamedAction.getMode() 
										+" "+blamedCommit.getRevisionHash().substring(0,8)
										+" --parent--> "+blamedCommitParent.substring(0,8)
										+" "+blamedActionParentPath);
							}
						} catch (Exception e) {
							logger.warn("FIX: "+e.getMessage());
							logger.warn("" 
									+" "+blamedAction.getMode() 
									+" "+blamedCommit.getRevisionHash().substring(0,8)
									+" --parent--> "+blamedCommitParent.substring(0,8)
									+" "+blamedActionParentPath
									+" Line: "+hbl.getSourceLine()
									+" Difference: "+d
									+" Blame size: "+blamedActionParentBlame.getResultContents().size()
									);
							
							e.printStackTrace();
						}
					} else {
						//TODO: investigate why this is the case - the blamed action is the first know action
						File blamedFile = adapter.getFile(blamedAction.getFileId());
						logger.warn("FIX: Blamed action is first action:"
								+" "+blamedAction.getMode() 
								+" "+blamedCommit.getRevisionHash()
								+" "+blamedFile.getPath());
					}
					
				} else {
					//hit -> keep blame line
				}
				
			} else {
				//not a copy / rename, keep blame line
			}
		}
		return hbl;
	}

	private LinkedHashMap<Integer, Hunk> getLinesPostMap(List<Hunk> blamedHunks) {
		LinkedHashMap<Integer, Hunk> linesPost = new LinkedHashMap<>();
		for (Hunk h : blamedHunks) {
			for (int line = h.getNewStart(); line < h.getNewStart()+h.getNewLines(); line++) {
				linesPost.put(line, h);
			}
		}
		return linesPost;
	}
	
	String getParentRevisionFromDB(String hash) {
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
	
	String getParentRevisionFromDB(String hash, String path) {
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
			logger.error(e.getMessage());
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
			HunkBlameLine hbl = getBlameLine(blame, line, h.getId());
			blameLines.add(hbl);
		}
		return blameLines;
	}

	HunkBlameLine getBlameLine(BlameResult blame, int line, ObjectId hunkId) {
		RevCommit sourceCommit = blame.getSourceCommit(line-1);
		Commit blamed = adapter.getCommit(sourceCommit.getName());
		
		HunkBlameLine hbl = new HunkBlameLine();
		hbl.setHunkId(hunkId);
		hbl.setHunkLine(line);
		//getSourceLine is 0-based, so line-1
		//returned line value is also 0-based, so +1
		//need to take gapLines into account -> no
		hbl.setSourceLine(blame.getSourceLine(line-1)+1);
		hbl.setBlamedCommitId(blamed.getId());
		if (!HumbleParameter.getInstance().isSkipSourcePaths()) {
			hbl.setSourcePath(blame.getSourcePath(line-1));
		}
		return hbl;
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
			} catch (Exception e) {
				logger.error("Revision "+hash+ " could not be found in the git repository");
				logger.error("  "+e.getMessage());
			}
		}
		return revisionCache.get(hash);
    }
	
	BlameResult getBlameResult(String hash, String path) throws Exception {
		BlameCommand blamer = new BlameCommand(repository);
		blamer.setStartCommit(getRevision(hash));
		blamer.setFilePath(path);
		//TODO: check differences in outcomes, as the setting may be unreliable in some cases
		blamer.setFollowFileRenames(!HumbleParameter.getInstance().isIgnoreRenames());
		BlameResult blame = blamer.call();
		return blame;
	}

	List<String> getOrderedRevisionHashes() {
		List<String> revisionHashes = new ArrayList<String>();
		try (Git git = new Git(repository)) {
            Iterable<RevCommit> revs = git.log().all().call();
            for (RevCommit rev : revs) {
            	revisionHashes.add(rev.getName());
            }
		} catch (Exception e) {
			e.printStackTrace();
		}

		Collections.reverse(revisionHashes);
		return revisionHashes;
	}
}
