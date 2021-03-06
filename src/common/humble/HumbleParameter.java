package common.humble;

import common.Parameter;

public class HumbleParameter extends Parameter {
	protected String version = "1.0.4";
	protected static HumbleParameter instance;

	// required parameter
	private String repoPath;
	private String url;

	// optional
	private String commit;
	
	private boolean processMerges;
	private boolean useActionBasedParent;
	private boolean skipCompression;
	private boolean skipSourcePaths;
	private boolean followCopies;
	private boolean ignoreRenames;


	public static synchronized HumbleParameter getInstance() {
		if (instance == null) {
			instance = new HumbleParameter();
		    instance.setOptionsHandler(new HumbleOptionHandler());
		    instance.setToolname("humbleSHARK");
		}
		return instance;
	}	
	
	@Override
	public void init(String args[]) {
		super.init(args);
		repoPath = cmd.getOptionValue("i");
		commit = cmd.getOptionValue("r");
		url = cmd.getOptionValue("u");
		processMerges = cmd.hasOption("M");
		useActionBasedParent = cmd.hasOption("ABP");
		skipCompression = cmd.hasOption("SC");
		skipSourcePaths = cmd.hasOption("SP");
		followCopies = cmd.hasOption("FC");
		ignoreRenames = cmd.hasOption("IR");
	}
	
	@Override
	protected void checkArguments() {
		super.checkArguments();
		if (!cmd.hasOption("u")  && !cmd.hasOption("i")) {
	        System.err.println("ERROR: Missing required options: u, i");
	        printHelp();
	        System.exit(1);
	    }
	}
	
	public String getRepoPath() {
	    checkIfInitialised();
	    return repoPath;
	}

	public String getCommit() {
	    checkIfInitialised();
	    return commit;
	}

	public String getUrl() {
	    checkIfInitialised();
	    return url;
	}

	public boolean isProcessMerges() {
	    checkIfInitialised();
		return processMerges;
	}

	public boolean isUseActionBasedParent() {
	    checkIfInitialised();
		return useActionBasedParent;
	}

	public boolean isSkipCompression() {
	    checkIfInitialised();
		return skipCompression;
	}

	public boolean isSkipSourcePaths() {
	    checkIfInitialised();
		return skipSourcePaths;
	}
	
	public boolean isFollowCopies() {
	    checkIfInitialised();
		return followCopies;
	}

	public boolean isIgnoreRenames() {
	    checkIfInitialised();
		return ignoreRenames;
	}

}
