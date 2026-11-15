# swing2mcp: notes for AI agents

Read [docs/SESSION-SUMMARY.md](docs/SESSION-SUMMARY.md) first: the goal, milestone history, current
state and next step. Design decisions: [DESIGN.md](DESIGN.md). Windows testing:
[WINDOWS-TEST.md](WINDOWS-TEST.md).

## Conventions

- Every finished milestone is committed; risky work goes on a branch and is fast-forwarded into `main`.
- Versions: the poms say `${revision}` (`999-SNAPSHOT`); never edit a version by hand. A release is a tag
  `vX.Y.Z` pushed to GitHub (see `.github/workflows/release.yml`); there are no milestone tags.

## Constraints

- `agent` and `maven-plugin` must stay Java 8 compatible (`maven.compiler.release=8`). The agent
  has no dependencies and never references Spring or other libraries at compile time.
- `mcp` targets Java 17 (MCP Java SDK). Nothing may write to its stdout except the MCP protocol.
- User-facing docs, commit messages, code, comments and tool descriptions are in English.

## Build and test

- `mvn install` at the repository root with JDK 17+.
- Try the demos: in `demo-app` or `demo-spring`, run `mvn swing2mcp:setup` then `mvn swing2mcp:run`
  (login: admin/admin).
- Drive the MCP server by hand: [scripts/mcp-steps.py](scripts/mcp-steps.py).
