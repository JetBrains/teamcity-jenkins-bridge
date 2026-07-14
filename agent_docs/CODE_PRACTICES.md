# Code Practices

The following is a series of practices to ensure that the code is correct, maintainable, and that it follows TeamCity's coding standards and conventions.

## Nullability

- All fields and method parameters must be annotated `@NotNull` or `@Nullable`. This lets IntelliJ IDEA flag potential NPEs and tells developers whether null is allowed.

## Naming conventions

- Fields must start with the `my` prefix.

## Documentation

- Every interface needs a Javadoc describing its purpose and anything implementers should know. Each method's arguments and return value should be described too.
- When writing any documentation (including comments and Javadoc), keep it short. State only what is necessary, a few lines at most.
- Do not use em dashes, semicolons, or Unicode characters in docs. Use only periods, commas, and "and".
- Use simple, plain, neutral words. Avoid slang, casual idioms, or filler words (for example, "spike", "moot", "bite", "classic", "genuinely").

## Testing

- The file structure of the source code is mirrored, and test classes are named `<class_under_test>Test`.
- Test methods should be named `<method_under_test>DoesSomething`.
- Example: For a class named `MyClass` with a method `doSomething`, the test class is named `MyClassTest` and the test method is named `doSomethingComputesSomethingCorrectly`.

## Refactoring

- Refactor only with a reason. Before a broad change, consider its effect on git blame. A file-wide "cleanup" commit buries the useful history other developers rely on when they annotate a line.

## Security

- Think about attack vectors from users, developers, project admins, and server admins.
- Escape properly for context (HTML, JS in HTML, JS in an HTML attribute, etc.).
- Never accept arbitrary redirect URLs. Restrict to a known, relative subset.
- Watch for injection (JS, SQL, XML), open redirects, zip bombs, and relative path tricks.
- Protect secrets (passwords, tokens) from exposure via settings, params, or env vars.
- Any new controller or API endpoint must check permissions.

## Performance

- Prefer bulk APIs over one-by-one operations.
- Avoid locking where many threads may contend, especially around long operations.
- Avoid soft references, their release timing is unpredictable.
- Prefer streaming/processing APIs over reading everything into memory.
- Use executors instead of raw threads.

## General practices

- All builds mirrored from Jenkins are agentless (server-side, no build agent). Keep this in mind when touching build lifecycle code.
- Prefer allowing self-service to requiring users to request help from the server admin, unless it is a security concern.
- Avoid solutions that require changes to TeamCity core unless absolutely necessary.
- Let users customize "magic" constants.
- Log meaningfully.
- Keep simple cases simple and complex cases possible. Good defaults should cover common use, without forcing users to configure things they do not need.
- Heuristics are fine if it is clear when and how they apply, with a description.
- Do not assume the happy path. Handle errors, hangs, and unexpected input.
- Do not assume XML input is valid, validate and check security on read.
