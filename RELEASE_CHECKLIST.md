# Release checklist

This checklist is for the maintainer. Do not tag a release until the checks are complete.

- [ ] Review all source and test files for correctness and originality.
- [ ] Confirm third-party dependency licenses and attribution.
- [ ] Scan the repository and Git history for credentials and private information.
- [ ] Run `./mvnw --batch-mode clean verify` on Java 21.
- [ ] Confirm GitHub Actions passes on a public repository.
- [ ] Run the example command from README and save the actual output.
- [ ] Check documented options against `--help` and verify exit codes on failure.
- [ ] Replace illustrative sample output with a captured, reproducible run or keep it clearly labeled.
- [ ] Check the package coordinates, artifact name, and release instructions.
- [ ] Confirm the README repository links match the published GitHub account.
- [ ] Create a small, independently reviewable first release tag only after verification.

## Evidence to attach to release notes

Record the commit SHA, OS, JDK version, full command, test inputs, and resulting report. Include known limitations and any tests that were not run.
