# AI Use and Design Decisions

## 1. What did you ask the AI to do, and what did you write or decide yourself?

I used AI to help break the assignment into steps, draft the first version of the Spring Boot service, add tests, and write the README. I also used it to run checks and catch mistakes while I was iterating.

I decided the final shape of the project: Java with Spring Boot, PostgreSQL for storage, a small code structure, and separate commits for each main step. I also edited the code, checked the tests, and chose the behavior for duplicate URLs and custom aliases.

## 2. Where did you override, correct, or throw away the AI's output, and why?

The first suggestion was Python, Flask, and SQLite. I changed that to Java and PostgreSQL because it matched the allowed stack better and felt more useful to explain in an interview-style assignment. I also decided not to use Kafka, Redis, or Aerospike because the core service does not need them yet.

I also corrected some generated code after testing it. The service now rejects bad JSON more clearly, handles URL formatting more carefully, and stops after a fixed number of attempts if it cannot create a code.

## 3. The two or three biggest trade-offs, and the alternatives considered

The biggest trade-off was code generation. I used a PostgreSQL sequence and Base62 conversion because it is simple and avoids duplicate codes. The downside is that the codes are predictable. I considered random codes or hashes, but those need more collision handling.

Another trade-off was returning the same short code when the same URL is submitted again. This keeps the database cleaner. The alternative was allowing multiple short links for the same URL, but that adds more rules for very little benefit in this version.

I also used direct PostgreSQL lookups for redirects. A cache would help at higher traffic, but for this assignment it would add complexity before it is really needed.

## 4. What is missing, or what would you do with another day?

With another day, I would add rate limiting, structured logs, CI, and a small load test. I would also add authentication so users can manage their own links, plus expiration and deletion rules. If the service had to handle much more traffic, I would add caching and read replicas next.
