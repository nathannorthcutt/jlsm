## Test-Driven Development

All implementation work follows a strict TDD cycle:

1. **Write tests first** — before creating any implementation class, write the test class covering the intended behaviour
2. **Confirm tests fail** — run the tests and verify they fail with a compilation error or assertion failure (not an infrastructure error); a test that passes before the implementation exists is a bad test
3. **Implement** — write the minimum implementation to make the tests pass
4. **Verify** — run the tests again and confirm all pass before committing

Never write an implementation class without a preceding failing test. Never skip the failure-confirmation step.
