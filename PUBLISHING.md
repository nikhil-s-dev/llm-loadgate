# Publish llm-loadgate

1. Review the source and remove any private information.
2. Run `./mvnw clean verify` (Windows: `mvnw.cmd clean verify`) and run the README example.
3. Create a **public, empty** GitHub repository named `llm-loadgate`. Do not initialize it with generated files.
4. From this project directory:

```bash
git init
git add .
git commit -m "Initial public release"
git branch -M main
git remote add origin https://github.com/nikhilsunku/llm-loadgate.git
git push -u origin main
```

5. Verify that GitHub Actions passes and inspect the README rendering.
6. Configure repository description, topics, and Issues; enable branch protection if desired.
7. Create a `v0.2.0` tag only when the CI, release workflow, and packaging have been verified.

Do not publish example outputs as real benchmarks unless you have reproduced them.

Suggested repository description: A Java CLI for measuring streaming LLM endpoint latency and enforcing service-level gates.
