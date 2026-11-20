## What and why

<!-- One or two sentences. Link the issue if there is one. -->

## Checklist

- [ ] Ran against a real AWS Emulator: the samples I touched (`mvn -q compile exec:java -Dexec.mainClass=...`) and `mvn verify`
- [ ] Every new or changed sample has a test in `src/test/java` that runs it against a private emulator
- [ ] Line coverage stays at or above 95% (`mvn verify` fails below it)
- [ ] Comments, the README table and the sample's printed output agree with what the code does
- [ ] New runnable samples have an IntelliJ run configuration in `.run/`
- [ ] No real credentials, account IDs, personal data or machine-specific paths (fictional values only)
