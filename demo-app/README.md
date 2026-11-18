# Demo application

A small legacy-style Swing application (Java 8, no Spring) to try swing2mcp on, without touching a
project of your own. It knows nothing about swing2mcp; its pom only lists the plugin.

You need JDK 17+ and Maven. In the repository root:

```bash
mvn install -DskipTests
cd demo-app
mvn swing2mcp:setup
mvn swing2mcp:run
```

Then tell your AI tool, for example:

> Log in with admin/admin, add a customer named Ali Veli from İzmir and check that it shows up in the table.

The login is `admin` / `admin`. What `setup` and `run` do is described in the
[main README](../README.md#quick-start-maven-project).
