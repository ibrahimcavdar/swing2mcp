# Security

## What swing2mcp can do

swing2mcp is a tool for development machines. Its agent runs inside the application's JVM and can read
what the application shows, operate it, and load recompiled classes into it (hot reload). Do not add the
agent to an application in production.

How access to the agent is limited:

- The agent is only placed into an application on an explicit request: `mvn swing2mcp:run`, `launch_app`,
  or `connect_app` with a process id. Nothing is injected in the background.
- It listens only on `127.0.0.1`, on a random port, and every request needs a random token.
- The port and token are written to `~/.swing2mcp/jvms/<pid>.properties` and deleted when the application
  exits. The file is readable only by its owner; on Windows this relies on the permissions of the user
  profile folder.
- The MCP server talks to the AI tool over stdio; it opens no network port.

Anyone who can read that file, which normally means a process running as the same user, can control the
application. That is the same level of access the Java Attach API already gives to such a process.

swing2mcp itself sends nothing off the machine. What the AI tool does with the snapshots and
screenshots it receives, including whether they leave the machine, depends on the AI tool and the model
you connect.

## Reporting a vulnerability

Please do not open a public issue. Use "Report a vulnerability" on the
[Security tab](https://github.com/ibrahimcavdar/swing2mcp/security) of the repository, which sends a
private report.

This is a one-person project. Reports are answered as soon as possible, but there is no guaranteed
response time, and only the latest release gets fixes. The software comes without warranty, as the
[license](LICENSE) states.
