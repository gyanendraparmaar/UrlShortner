# AI Use and Design Decisions

## 1. What did you ask the AI to do, and what did you write or decide yourself?

I asked the AI to read the assignment and the URL-shortener chapter from Alex Xu, turn both into an implementation plan, generate the service and tests, run the project, review failures, write the setup documentation, and preserve the work as several meaningful commits. The AI produced most of the initial code and prose; I did not pretend that it was only used for autocomplete.

My main contribution was direction and judgment. I required Java and chose PostgreSQL from the allowed database options, approved the API and duplicate behavior before implementation, required a minimal structure that I could explain, and required each logical stage to be committed and pushed without squashing. I also decided that Redis/Aerospike and Kafka were not justified by the assignment. I reviewed and approved the contract: exact `POST /shorten` and `GET /{code}` routes, 301 redirects, seven-character Base62 codes, normalized duplicate detection, and explicit custom-alias conflicts.

## 2. Where did you override, correct, or throw away the AI's output — and why?

The AI first proposed Python, Flask, and SQLite because they were the shortest route to a working take-home. I rejected that plan and required Java with PostgreSQL so the solution matched the stack I wanted to demonstrate. I kept Spring Boot and plain JDBC but rejected adding Redis, Aerospike, or Kafka merely because they were available; none contributes to the required write/redirect path at this scale.

Testing also caused a concrete correction. The first generated controller relied on a Jackson configuration flag to reject a numeric JSON `url`. A test showed that Jackson still coerced the number to a string. That assumption was discarded and the controller now checks JSON node types explicitly before invoking the service. The remote repository also contained an initial README commit that was absent locally; instead of force-pushing it away, I preserved it with a merge and replaced its placeholder content in the documentation stage.

## 3. The two or three biggest trade-offs, and the alternatives considered

**Sequence plus Base62 versus hashing or random codes.** A PostgreSQL sequence followed by Base62 conversion is easy to reason about: IDs are unique across concurrent application instances and Base62 is one-to-one. Starting at `62^6` produces the seven-character space described by Alex Xu. The trade-off is predictability—codes can be enumerated—and dependence on one database sequence. I considered a truncated cryptographic hash with collision retries and random secure codes, but both need repeated collision checks and make the collision argument less direct for this exercise. At much larger distributed scale, I would replace the database sequence with a global ID service.

**One normalized URL, one mapping.** Repeating a URL returns the existing code, matching Alex Xu's shortening flow and avoiding duplicate rows. If a caller asks for a different custom alias after that URL already exists, the service returns `409` and exposes the existing code instead of silently ignoring the request. The alternative was allowing multiple aliases per URL, which is flexible but changes the data model and makes duplicate behavior less clear.

**PostgreSQL-only reads and 301 redirects.** Direct database reads keep the submission small and correct. Redis or Aerospike could implement cache-aside lookups when redirects dominate traffic, but would introduce invalidation and availability decisions now. The assignment requires 301; it reduces repeat server load because browsers may cache it, but it also means repeat clicks can bypass the service. A 302 would make click analytics more complete. Kafka was therefore deferred along with analytics rather than adding an event pipeline that a 301 response cannot observe reliably.

## 4. What is missing, or what would you do with another day?

I would add rate limiting and abuse controls, metrics and structured request logging, load tests, and production deployment manifests. For scale, I would introduce cache-aside redirect lookup, database replicas/sharding, and a distributed ID generator, then test behavior when the cache or database is unavailable. Product work would include expiration/deletion rules, authenticated ownership, safe-link or blocklist checks, and an analytics design that deliberately revisits the 301/302 decision. I would also add CI so every pushed commit runs the full PostgreSQL Testcontainers suite automatically.
