package common.humble;

import java.util.ArrayList;
import java.util.Properties;

import common.ConfigurationHandler;

public class HumbleConfigurationHandler extends ConfigurationHandler {
	private static HumbleConfigurationHandler instance;

	public static synchronized HumbleConfigurationHandler getInstance() {
		if (instance == null) {
			instance = new HumbleConfigurationHandler();
		}
		return instance;
	}	

	@Override
	protected ArrayList<String> convertProperties(Properties properties) {
		ArrayList<String> props = new ArrayList<>();
		props.addAll(super.convertProperties(properties));
		props.add("-u");
		props.add(properties.getProperty("url"));
		props.add("-i");
		props.add(properties.getProperty("git"));
		if (properties.containsKey("revision")) {
			props.add("-r");
			props.add(properties.getProperty("revision"));
		}
		if (properties.containsKey("skip_compression")) {
			props.add("-SC");
		}
		if (properties.containsKey("merges")) {
			props.add("-M");
		}
		if (properties.containsKey("action_based_parents")) {
			props.add("-ABP");
		}
		if (properties.containsKey("skip_source_paths")) {
			props.add("-SP");
		}
		return props;
	}
}
