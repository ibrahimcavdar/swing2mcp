package io.github.ibrahimcavdar.swing2mcp.agent;

import java.io.File;

final class AgentOptions {

    File registrationDir = new File(System.getProperty("user.home"), ".swing2mcp" + File.separator + "jvms");
    int port = 0;

    static AgentOptions parse(String args) {
        AgentOptions options = new AgentOptions();
        if (args == null || args.trim().length() == 0) {
            return options;
        }
        for (String pair : args.split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            if ("dir".equals(key)) {
                options.registrationDir = new File(value);
            } else if ("port".equals(key)) {
                options.port = Integer.parseInt(value);
            }
        }
        return options;
    }
}
