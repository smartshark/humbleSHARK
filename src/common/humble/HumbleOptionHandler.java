package common.humble;

import org.apache.commons.cli.Option;

import common.OptionHandler;

/**
 * In this class options representing all possible program arguments are defined.
 *
 * @author <a href="mailto:dhonsel@informatik.uni-goettingen.de">Daniel Honsel</a>
 * @author <a href="mailto:makedonski@informatik.uni-goettingen.de">Philip Makedonski</a>
 */

public class HumbleOptionHandler extends OptionHandler {

	@Override
	protected void initOptions() {
		super.initOptions();
	    Option option;

		option = new Option("u", "URL of the project (e.g., https://github.com/smartshark/humbleSHARK). Required.");
	    option.setRequired(false);
	    option.setLongOpt("url");
	    option.setArgs(1);
	    option.setArgName("url");
	    options.addOption(option);

	    option = new Option("r", "Hash of the revision that is analyzed. If not provided, all revisions of the project will be analysed.");
	    option.setRequired(false);
	    option.setLongOpt("rev");
	    option.setArgs(1);
	    option.setArgName("revision_hash");
	    options.addOption(option);

	    option = new Option("i", "Path to the repository that should be analyzed. Required.");
	    option.setRequired(false);
	    option.setLongOpt("input");
	    option.setArgs(1);
	    option.setArgName("path");
	    options.addOption(option);

		option = new Option("M", "Process merges. Default: No");
	    option.setRequired(false);
	    option.setLongOpt("merges");
	    option.setArgs(0);
	    options.addOption(option);

		option = new Option("SC", "Skip compression. Default: No");
	    option.setRequired(false);
	    option.setLongOpt("skip_compression");
	    option.setArgs(0);
	    options.addOption(option);

		option = new Option("ABP", "Use action-based parents. Default: No");
	    option.setRequired(false);
	    option.setLongOpt("action_based_parents");
	    option.setArgs(0);
	    options.addOption(option);

		option = new Option("SP", "Do not save source paths within blamed commit. Default: No");
	    option.setRequired(false);
	    option.setLongOpt("skip_source_paths");
	    option.setArgs(0);
	    options.addOption(option);

		option = new Option("FC", "Follow copies. Default: No");
	    option.setRequired(false);
	    option.setLongOpt("follow_copies");
	    option.setArgs(0);
	    options.addOption(option);

	    option = new Option("IR", "Ignore renames. Default: No");
	    option.setRequired(false);
	    option.setLongOpt("ignore_renames");
	    option.setArgs(0);
	    options.addOption(option);
	}
}
