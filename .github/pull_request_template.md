## What and why

<!-- What changes, and what problem it solves. Link the issue if there is one. -->

## How it was checked

<!-- Which steps of docs/manual-testing.md, on which Java version and operating system. -->

## Checklist

- [ ] `mvn install` passes, including the end-to-end tests
- [ ] A bug fix or new tool has a test in `e2e-tests`, where the demo application can show it
- [ ] `agent` and `maven-plugin` still compile for Java 8, and `agent` has no new dependency
- [ ] Notable changes are listed under "Unreleased" in `CHANGELOG.md`
