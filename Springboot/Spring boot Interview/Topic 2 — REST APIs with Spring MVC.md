# Topic 2 — REST APIs with Spring MVC

Oct 7, 2026 · @Mayur Patle

## What this topic tests

At 2 YOE you are expected to *design* an API, not just write a controller. Interviewers probe three things: do you know what happens between the HTTP request and your method, do you return correct and consistent errors, and do you think about clients — retries, versioning, pagination, concurrent edits.

Same format as Topic 1: **Answer** (spoken, 60–90 s) → **Code** → **Follow-ups**. Close each answer with an example from your own service.

## Q1. What happens from the moment an HTTP request hits your Spring Boot app until the JSON response goes out?

**Answer.** Spring MVC is built on the **Front Controller** pattern — one servlet, the `DispatcherServlet`, receives every request and delegates.

1. **Tomcat** accepts the connection and assigns a worker thread (thread-per-request; default pool 200).
2. **Servlet filter chain** runs — Spring Security, CORS, logging/MDC filters.
3. **`DispatcherServlet`** asks the **`HandlerMapping`** which controller method matches the URL + HTTP method (`@GetMapping("/orders/{id}")`).
4. **`HandlerInterceptor.preHandle()`** runs.
5. **`HandlerAdapter`** invokes the method. Before that, **argument resolvers** build the parameters: `@PathVariable`, `@RequestParam`, and `@RequestBody` via an **`HttpMessageConverter`** (Jackson turns JSON into a DTO). `@Valid` triggers validation here.
6. Your controller → service → repository runs.
7. The return value goes through **return-value handlers**; for `@RestController`, Jackson's `HttpMessageConverter` serializes it to JSON based on the `Accept` header.
8. **`postHandle()`** and **`afterCompletion()`** interceptor hooks run.
9. If any step throws, **`HandlerExceptionResolver`** — which is where `@RestControllerAdvice` plugs in — builds the error response.

Practical value: when a request fails, this flow tells me where to look. A 401 before my log line means the security filter rejected it; a 400 with no controller log means deserialization or validation failed in step 5; a 415 means no converter matched the `Content-Type`.

**Follow-ups**

- *Is the controller a singleton? Is it thread-safe?* Yes, singleton; safe only because it holds no per-request state.
- *What is the ViewResolver?* Used for server-rendered views (`@Controller` + Thymeleaf); skipped for `@RestController`.
- *How do you handle 10k concurrent slow requests?* Thread pool tuning, async (`CompletableFuture`/`DeferredResult`), WebFlux, or Java 21 virtual threads (`spring.threads.virtual.enabled=true` in Boot 3.2+).

## Q2. How do you handle exceptions globally in a REST API?

**Answer.** I never put try/catch in controllers. Services throw meaningful **domain exceptions** (`OrderNotFoundException`, `InsufficientBalanceException`), and one **`@RestControllerAdvice`** class maps each to an HTTP status and a consistent error body.

For the body I use **`ProblemDetail`** (RFC 9457, built into Spring 6 / Boot 3): `type`, `title`, `status`, `detail`, `instance`, plus custom properties like `errorCode` and `traceId`. Every client — frontend, mobile, other services — parses one shape.

Three rules I follow:

- **Specific handlers first, a catch-all `Exception` handler last** that returns 500 with a generic message. Never leak stack traces or SQL to the client.
- **Log at the right level** — 4xx is the client's fault (WARN or INFO), 5xx is ours (ERROR with stack trace).
- **Include the trace ID** so support can find the exact request in logs.

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(OrderNotFoundException.class)
    public ProblemDetail notFound(OrderNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setTitle("Order not found");
        pd.setProperty("errorCode", "ORD-404");
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail validation(MethodArgumentNotValidException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Validation failed");
        pd.setProperty("errors", ex.getBindingResult().getFieldErrors().stream()
            .collect(Collectors.toMap(FieldError::getField,
                     fe -> String.valueOf(fe.getDefaultMessage()), (a, b) -> a)));
        return pd;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail fallback(Exception ex) {
        log.error("Unhandled error", ex);
        return ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong");
    }
}
```

**Follow-ups**

- *`@ControllerAdvice` vs `@RestControllerAdvice`?* The latter adds `@ResponseBody`.
- *`@ResponseStatus` on the exception class?* Works for simple cases, but couples the domain layer to HTTP; the advice keeps that mapping in the web layer.
- *Errors thrown in a filter (e.g. Spring Security) — does the advice catch them?* No; filters run before `DispatcherServlet`. Use `AuthenticationEntryPoint` / `AccessDeniedHandler` for security errors.
- *Checked or unchecked domain exceptions?* Unchecked — they also trigger `@Transactional` rollback by default.

## Q3. How do you validate incoming requests? @Valid vs @Validated?

**Answer.** I use **Jakarta Bean Validation** (Hibernate Validator, via `spring-boot-starter-validation`) in two layers:

- **Structural validation at the edge** — annotations on the request DTO: `@NotBlank`, `@Email`, `@Positive`, `@Size`, `@Pattern`. A bad request is rejected with 400 before it touches business logic.
- **Business validation in the service** — "does this account have enough balance", "is this coupon still active". These need the database, so they don't belong in annotations; they throw domain exceptions.

**`@Valid` vs `@Validated`:**

|  | `@Valid` (Jakarta) | `@Validated` (Spring) |
| --- | --- | --- |
| Where | `@RequestBody` params, nested fields | On the class |
| Nested objects | Yes — put `@Valid` on the nested field to cascade | — |
| Validation groups | No | Yes — e.g. `OnCreate` vs `OnUpdate` |
| Method-level (`@PathVariable`, `@RequestParam`, service methods) | Needs `@Validated` on the class | Enables it via an AOP proxy |

Error types differ, so the advice handles both: `@RequestBody` failures throw `MethodArgumentNotValidException`; method-level failures throw `ConstraintViolationException` (in Spring 6.1+ often `HandlerMethodValidationException`).

```java
public record CreateOrderRequest(
    @NotBlank String customerId,
    @NotEmpty List<@Valid OrderItem> items,
    @Positive BigDecimal amount) {}

@RestController
@Validated
@RequestMapping("/orders")
class OrderController {
    @PostMapping
    ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest req) { ... }

    @GetMapping("/{id}")
    OrderResponse get(@PathVariable @Positive Long id) { ... }
}
```

**Custom validator** — for a rule reused across DTOs (e.g. valid IFSC code): a `@ValidIfsc` annotation with `@Constraint(validatedBy = IfscValidator.class)`, and `IfscValidator implements ConstraintValidator<ValidIfsc, String>`.

**Follow-ups**

- *Cross-field validation (end date after start date)?* Class-level custom constraint on the DTO.
- *Why not validate only on the frontend?* The API is a public contract; any client, script, or attacker can call it directly.

## Q4. Explain safe and idempotent HTTP methods. PUT vs PATCH vs POST?

**Answer.** **Safe** means the request doesn't change server state. **Idempotent** means sending it once or ten times leaves the server in the same state. This matters because networks fail and clients, gateways and service meshes **retry** — only idempotent requests are safe to retry blindly.

| Method | Safe | Idempotent | Use |
| --- | --- | --- | --- |
| GET | Yes | Yes | Read a resource |
| PUT | No | Yes | Replace the whole resource at a known URI |
| DELETE | No | Yes | Remove (second call → 404 or 204, state unchanged) |
| PATCH | No | Not guaranteed | Partial update |
| POST | No | No | Create, or trigger an action |

- **PUT** sends the full representation; missing fields are set to null/default. `PUT /users/42` twice → same result.
- **PATCH** sends only changed fields. It's idempotent for "set status = SHIPPED", but not for "increment quantity by 1".
- **POST** creates a new resource each time — two retries can mean two orders or two payments. Q10 covers how to make it safe.

**Resource naming** I follow: plural nouns, no verbs — `GET /orders/{id}/items`, not `/getOrderItems`. For actions that aren't CRUD, a sub-resource: `POST /orders/{id}/cancellation`.

**`@PathVariable` vs `@RequestParam`:** path variables **identify** a resource (`/orders/{id}`); query params **filter, sort or page** a collection (`/orders?status=PAID&page=0`). Required vs optional follows from that.

**Follow-ups**

- *Can GET have a body?* Technically allowed but semantically undefined; proxies may drop it. Use POST `/search` for complex queries.
- *What does POST return on create?* `201 Created` + `Location: /orders/123` header + the created resource.

## Q5. Which status codes do you return, and when? 401 vs 403? 400 vs 422?

**Answer.** Status codes are part of the contract — clients, gateways and retry logic make decisions on them, so "everything is 200 with an error flag" breaks retries, monitoring and caching.

| Code | When I use it |
| --- | --- |
| 200 OK | Successful GET / PUT / PATCH with a body |
| 201 Created | POST created a resource; add `Location` header |
| 202 Accepted | Request queued for async processing (e.g. report generation via Kafka) |
| 204 No Content | Successful DELETE or update with no body |
| 400 Bad Request | Malformed JSON, missing or invalid fields |
| 401 Unauthorized | **Not authenticated** — no token, expired or invalid token |
| 403 Forbidden | **Authenticated, but not allowed** — a USER calling an ADMIN endpoint |
| 404 Not Found | Resource doesn't exist (or you hide its existence from this user) |
| 409 Conflict | State conflict — duplicate email, version mismatch, order already cancelled |
| 412 Precondition Failed | `If-Match` ETag didn't match (Q10) |
| 422 Unprocessable Content | Syntax valid but business rule fails — used by some teams instead of 400 |
| 429 Too Many Requests | Rate limit hit; add `Retry-After` |
| 500 Internal Server Error | Unexpected bug on our side |
| 502 / 503 / 504 | Downstream failed / service unavailable / downstream timeout |

**The two that trip people up:**

- **401 vs 403** — 401 asks "who are you?", 403 says "I know who you are, and no." Re-logging in fixes a 401, never a 403.
- **400 vs 422** — both are defensible; what matters is that the team picks one convention and applies it everywhere. I use 400 for validation errors and 409/422 for business-rule violations.

The rule that matters for resilience: **4xx = don't retry** (the request itself is wrong), **5xx and 429 = retry with backoff**. That's why returning 500 for a validation error causes useless retry storms.

**Follow-ups**

- *404 or 403 for another user's order?* Often 404, so you don't reveal that order ID exists.
- *Return 200 with empty list or 404 for an empty search?* 200 with `[]` — the collection exists, it's just empty.

## Q6. How do you version a REST API? How do you avoid breaking clients?

**Answer.** First principle: **version as rarely as possible**. Most changes can be made backward-compatible, and I only bump the version for a genuinely breaking change.

**Non-breaking (no new version):** adding an optional field to a request, adding a field to a response, adding a new endpoint. This works only if clients are tolerant readers — Jackson's `FAIL_ON_UNKNOWN_PROPERTIES = false`, which Spring Boot sets by default.

**Breaking (needs a version):** removing or renaming a field, changing a type, making an optional field required, changing status codes or semantics.

| Strategy | Example | Pros | Cons |
| --- | --- | --- | --- |
| URI path | `/api/v1/orders` | Obvious, easy to route at gateway, cache-friendly | URI should identify a resource, not a version |
| Request header | `X-API-Version: 2` | Clean URIs | Invisible in browser/logs, easy to forget |
| Media type | `Accept: application/vnd.shop.v2+json` | Most "RESTful" | Hardest for clients and tooling |
| Query param | `/orders?version=2` | Simple | Mixes with filters, caching issues |

I use **URI versioning** in practice — it's what most teams (and API gateways) standardize on, and it's explicit in logs and dashboards.

**Retiring v1:** run v1 and v2 side by side, mark v1 with `Deprecation` and `Sunset` response headers, track v1 traffic per client in metrics, and remove it only when traffic reaches zero. Internally, v1 and v2 controllers share the same service layer — only DTOs and mappers differ.

**Follow-ups**

- *Does Spring support versioning natively?* Spring Framework 7 / Boot 4 adds first-class API versioning (`version` attribute on mappings); before that you do it via paths or custom conditions.
- *Versioning between internal microservices?* Same rules, plus consumer-driven contract tests (Spring Cloud Contract / Pact) to catch breaks in CI.

## Q7. How do you implement pagination, sorting and filtering? Offset vs cursor pagination?

**Answer.** Any endpoint that returns a collection must be paginated with a **server-enforced max page size** — otherwise one `GET /transactions` can pull a million rows into memory and take the pod down.

In Spring Data, the controller accepts a `Pageable` (`?page=0&size=20&sort=createdAt,desc`) and passes it to the repository.

|  | Offset (`page` + `size`) | Cursor / keyset (`after=<last id>`) |
| --- | --- | --- |
| SQL | `LIMIT 20 OFFSET 100000` | `WHERE (created_at, id) < (?, ?) ORDER BY ... LIMIT 20` |
| Deep pages | Slow — DB still scans and discards 100k rows | Constant time with an index |
| Data changes during paging | Rows shift → duplicates or skips | Stable |
| Jump to page 50 | Yes | No — next/previous only |
| Total count | Available (extra `COUNT(*)` query) | Usually not |
| Fits | Admin tables, small datasets | Feeds, transaction history, infinite scroll, exports |

Two practical details:

- **`Page` vs `Slice`** — `Page` runs a `COUNT(*)` for total pages, which is expensive on big tables. If the UI only needs "load more", return a `Slice`.
- **Whitelist sort fields** — never pass arbitrary client sort fields to the DB; an unindexed sort on a big table is a self-inflicted outage.

```java
@GetMapping
public Page<OrderResponse> list(
        @RequestParam(required = false) OrderStatus status,
        @PageableDefault(size = 20, sort = "createdAt",
                         direction = Sort.Direction.DESC) Pageable pageable) {
    return orderService.search(status, pageable).map(mapper::toResponse);
}

// application.yml
// spring.data.web.pageable.max-page-size: 100
```

For dynamic filters (status, date range, amount range) I use **Specifications** or a Criteria-based query, not one repository method per combination.

**Follow-ups**

- *Why shouldn't you return `Page<Entity>` directly?* It leaks the entity and Spring's internal JSON shape; map to DTOs (Q8). Boot 3.3+ warns and offers `PagedModel` for a stable shape.
- *Cursor format?* Encode the last row's sort keys as an opaque Base64 token so clients can't tamper with it.

## Q8. Why use DTOs instead of returning JPA entities from controllers?

**Answer.** The entity models the **database**; the DTO models the **API contract**. Coupling them causes real bugs:

1. **Data leaks** — return a `User` entity and you've serialized `passwordHash`, internal flags, audit columns.
2. **Lazy-loading failures** — Jackson touches a lazy collection after the transaction closed → `LazyInitializationException`, or, with Open-Session-in-View on, a burst of hidden queries (N+1) during serialization.
3. **Infinite recursion** — bidirectional `Order ↔ OrderItem` relationships loop in Jackson. `@JsonIgnore` hacks then spread into the domain.
4. **Mass assignment** — binding a request straight onto an entity lets a client set `role=ADMIN` or `balance=1000000`.
5. **Contract coupling** — renaming a column now breaks every API client. With DTOs, the schema and the API evolve independently.

So: request DTO in → service works with entities inside the transaction → response DTO out. I use **Java records** for DTOs (immutable, concise) and **MapStruct** for mapping — it generates plain Java at compile time, so it's fast, type-checked, and fails the build on an unmapped field, unlike reflection-based ModelMapper.

```java
public record OrderResponse(Long id, String status,
                            BigDecimal total, Instant createdAt) {}

@Mapper(componentModel = "spring")
public interface OrderMapper {
    OrderResponse toResponse(Order order);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "status", constant = "CREATED")
    Order toEntity(CreateOrderRequest req);
}
```

**Follow-ups**

- *Where should mapping happen — controller or service?* Either is defensible; I map in the service so it returns DTOs and lazy fields are read inside the transaction.
- *What is Open-Session-in-View and should you disable it?* It keeps the Hibernate session open through rendering; Boot enables it by default with a warning. I set `spring.jpa.open-in-view=false` so lazy-loading bugs surface in tests, not as hidden queries in production.
- *Isn't one DTO per endpoint too much code?* Records + MapStruct keep it small; the safety is worth it.

## Q9. Filter vs HandlerInterceptor vs AOP — when do you use each?

**Answer.** All three handle cross-cutting concerns; the difference is **where they sit** in the request path (see Q1) and **what they can see**.

|  | Servlet `Filter` | `HandlerInterceptor` | AOP `@Aspect` |
| --- | --- | --- | --- |
| Layer | Servlet container, before `DispatcherServlet` | Spring MVC, around the controller | Any Spring bean method |
| Sees | Raw request/response | Request + **which handler** (method, annotations) | Method args and return value |
| Runs for | Every request, incl. static files and errors | Only requests mapped to a controller | Matched method calls, HTTP or not |
| Can modify body | Yes (wrap request/response) | No (body already read) | Args/return values |
| Typical use | Security, CORS, correlation ID/MDC, request logging, compression | Per-endpoint auth checks, tenant resolution, rate limiting by handler annotation | `@Transactional`, auditing, timing, retry on service methods |

How I'd decide: if it's about **HTTP itself** (headers, every request, before Spring sees it) → filter. If it needs to know **which controller method** is being called → interceptor. If it's about **business methods** regardless of how they're called (HTTP, Kafka listener, scheduler) → aspect.

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req,
            HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String id = Optional.ofNullable(req.getHeader("X-Correlation-Id"))
                            .orElse(UUID.randomUUID().toString());
        MDC.put("correlationId", id);
        res.setHeader("X-Correlation-Id", id);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.clear();   // threads are pooled — always clean up
        }
    }
}
```

**Follow-ups**

- *Why `OncePerRequestFilter`?* A plain filter can run twice on forwards/error dispatches.
- *Why the `finally` with `MDC.clear()`?* Tomcat reuses threads; without it the next request logs the previous request's ID.
- *How do you order filters?* `@Order` or `FilterRegistrationBean.setOrder()`.

## Q10. A client retries a POST /payments after a timeout. How do you avoid charging twice? And how do you stop two users overwriting each other's update?

**Answer — part 1: Idempotency-Key.** The timeout means the client doesn't know if the first request succeeded. So the client generates a unique `Idempotency-Key` (UUID) per logical operation and sends the same key on every retry. The server:

1. Looks up the key. **Not found** → atomically insert it with status `IN_PROGRESS` and process the payment.
2. On completion, store the **response** (status + body) against the key, with a TTL (e.g. 24 h).
3. **Found, completed** → return the stored response without re-processing.
4. **Found, in progress** → return `409 Conflict` (or wait) so two concurrent retries can't both execute.
5. **Same key, different body** → `422`, the client is misusing the key.

The atomic "insert if absent" is the critical part — a DB **unique constraint** on the key, or Redis `SET key value NX EX 86400`. A check-then-insert without atomicity has a race window. Stripe's API works this way.

```java
@PostMapping("/payments")
public ResponseEntity<PaymentResponse> pay(
        @RequestHeader("Idempotency-Key") String key,
        @Valid @RequestBody PaymentRequest req) {

    Boolean firstTime = redis.opsForValue()
        .setIfAbsent("idem:" + key, "IN_PROGRESS", Duration.ofHours(24));

    if (Boolean.FALSE.equals(firstTime)) {
        return idempotencyStore.cachedResponse(key)          // completed
            .orElseThrow(() -> new RequestInProgressException(key)); // 409
    }
    PaymentResponse res = paymentService.process(req);
    idempotencyStore.saveResponse(key, res);
    return ResponseEntity.status(HttpStatus.CREATED).body(res);
}
```

**Answer — part 2: lost updates with ETag.** Two users load order v5, both edit, both PUT — the second silently overwrites the first. Fix with **optimistic concurrency**:

- Entity has a `@Version` column. `GET` returns `ETag: "5"`.
- Client sends `PUT` with `If-Match: "5"`.
- Server compares; if the current version is 6 → **`412 Precondition Failed`**, client re-fetches and retries. JPA's `@Version` also guards the DB write itself (`UPDATE ... WHERE version = 5`).

**Follow-ups**

- *Where's the payment idempotency stored if Redis is down?* A DB table with a unique key is more durable; Redis is faster. For money, I'd use the DB, ideally in the same transaction as the payment.
- *Who generates the key — client or server?* The client; only it knows two requests are the same logical attempt.
- *Optimistic vs pessimistic locking?* Covered in the JPA and Transactions topics.

## Rapid revision sheet

| # | Question | One-line answer |
| --- | --- | --- |
| 1 | Request flow | Tomcat thread → filters → DispatcherServlet → HandlerMapping → interceptor → arg resolvers/Jackson/@Valid → controller → converter → response |
| 2 | Exceptions | Domain exceptions + one `@RestControllerAdvice` returning `ProblemDetail`; catch-all 500, no stack traces |
| 3 | Validation | Annotations on DTO for shape, service for business rules; `@Validated` for groups and path/query params |
| 4 | Methods | GET/PUT/DELETE idempotent, POST not, PATCH maybe; path = identity, query = filter |
| 5 | Status codes | 401 not logged in, 403 not allowed, 409 conflict, 412 ETag mismatch; 4xx don't retry, 5xx/429 retry |
| 6 | Versioning | Avoid breaking changes; URI `/v1`; deprecate with `Sunset` header, remove at zero traffic |
| 7 | Pagination | Enforce max size; offset for small/admin, keyset for large/feeds; `Slice` avoids COUNT |
| 8 | DTOs | Prevent leaks, lazy-load errors, recursion, mass assignment; records + MapStruct |
| 9 | Filter/Interceptor/AOP | HTTP-level / handler-aware / bean-method-level |
| 10 | Duplicate POST | Idempotency-Key with atomic insert + stored response; ETag + `If-Match` + `@Version` for lost updates |

**Curveballs to prepare one line for**

- How do you configure CORS for a React frontend on another domain?
- `RestTemplate` vs `WebClient` vs `RestClient` — which would you use today? (`RestClient` for blocking calls in Boot 3.2+.)
- How do you document your API? (springdoc-openapi → Swagger UI / OpenAPI spec.)
- What is HATEOAS, and have you used it? (Honest answer: rarely used in practice.)
- How would you upload a 2 GB file? (Multipart streaming or pre-signed S3 URL so the file never passes through your service.)
