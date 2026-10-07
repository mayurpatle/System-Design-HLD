# Topic 1 — Spring Core & Spring Boot Fundamentals

Oct 7, 2026 · @Mayur Patle

## How to use this doc

At 2 YOE, interviewers use this topic to check one thing: do you understand what Spring does *for* you, or have you only used annotations? Every answer below is written to be spoken in 60–90 seconds, then backed by a code snippet and the follow-ups you should expect.

- **Answer** — the ideal spoken answer, structured definition → how it works → why it matters.
- **Code** — the minimum snippet that proves you have actually written it.
- **Follow-ups** — the next questions an interviewer usually asks; prepare one line for each.

Tip: always close an answer with a real example from your project ("In our order service, we…"). That one sentence separates a 2 YOE candidate from a fresher.

## Q1. What is IoC and Dependency Injection? Which injection type do you prefer and why?

**Answer.** Inversion of Control means my classes don't create or look up their own dependencies — the Spring container does, and hands them in. Dependency Injection is *how* Spring implements IoC: the `ApplicationContext` reads bean definitions, builds the object graph, and injects each dependency through a constructor, setter, or field.

I prefer **constructor injection**, and Spring's own docs recommend it, for four reasons:

1. **Immutability** — dependencies can be `final`, so the object is fully built and thread-safe after construction.
2. **No hidden nulls** — the object can't exist without its required dependencies.
3. **Testability** — I can write `new OrderService(mockRepo)` in a unit test without starting Spring or using reflection.
4. **Design smell detector** — a constructor with 8 parameters tells me the class does too much.

Setter injection is fine for genuinely *optional* dependencies. Field injection (`@Autowired` on a field) hides dependencies, can't be `final`, and forces reflection in tests, so I avoid it in production code.

```java
@Service
public class OrderService {
    private final OrderRepository repo;
    private final PaymentClient payments;

    // single constructor → @Autowired is optional since Spring 4.3
    public OrderService(OrderRepository repo, PaymentClient payments) {
        this.repo = repo;
        this.payments = payments;
    }
}
```

**Follow-ups**

- *Is* `@Autowired` *needed on a single constructor?* No, not since Spring 4.3.
- *BeanFactory vs ApplicationContext?* ApplicationContext extends BeanFactory and adds eager singleton creation, events, i18n, AOP integration. You always use ApplicationContext in Boot.
- *Does constructor injection help with circular dependencies?* Yes — it fails fast at startup instead of hiding the cycle (see Q7).



## Q2. What does @SpringBootApplication do, and how does auto-configuration actually work?

**Answer.** `@SpringBootApplication` is a meta-annotation combining three:

- `@SpringBootConfiguration` — marks the class as a `@Configuration` source.
- `@ComponentScan` — scans the main class's package and all sub-packages for `@Component` classes.
- `@EnableAutoConfiguration` — turns on auto-configuration.

Auto-configuration works like this:

1. Every starter jar ships a file `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (Boot 3; Boot 2 used `spring.factories`) listing auto-config classes.
2. Spring loads all of them as candidates — there are 150+.
3. Each class is guarded by **conditional annotations**, so it only applies when it makes sense: `@ConditionalOnClass(DataSource.class)` (the jar is on the classpath), `@ConditionalOnMissingBean` (you haven't defined your own), `@ConditionalOnProperty` (a property is set).
4. Matching configs register beans, with defaults bound from `application.yml`.

The key design idea is **"back-off"**: because of `@ConditionalOnMissingBean`, any bean I define myself wins, and Boot's default steps aside. That's why adding `spring-boot-starter-data-jpa` plus a datasource URL gives me a working `DataSource`, `EntityManagerFactory` and `TransactionManager` with zero code.

```java
@AutoConfiguration
@ConditionalOnClass(ObjectMapper.class)
public class JacksonAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean   // backs off if you define your own
    public ObjectMapper objectMapper() { return new ObjectMapper(); }
}
```

**Follow-ups**

- *How do you see which auto-configs applied?* Run with `--debug` (the Conditions Evaluation Report) or hit `/actuator/conditions`.
- *How do you disable one?* `@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)` or `spring.autoconfigure.exclude`.
- *What if a bean in another package isn't picked up?* It's outside the scan base package — move it under the main class's package or add `scanBasePackages`.
- *Can you write a custom starter?* Yes — an auto-config class plus the `.imports` file, packaged as a jar.



## Q3. What are bean scopes? What happens when you inject a prototype bean into a singleton?

**Answer.** Scope decides how many instances the container creates and how long they live.


| Scope               | Instances                                | Typical use                               |
| ------------------- | ---------------------------------------- | ----------------------------------------- |
| singleton (default) | One per ApplicationContext               | Stateless services, repositories, clients |
| prototype           | New one on every `getBean()` / injection | Stateful, short-lived helpers             |
| request             | One per HTTP request                     | Request-scoped context (web only)         |
| session             | One per HTTP session                     | User session data (web only)              |
| application         | One per ServletContext                   | Rarely used                               |


Two points interviewers look for:

- **Spring singleton ≠ GoF singleton.** It's one instance *per container*, not per JVM/classloader. And it's not thread-safe by itself — I keep singletons stateless (no mutable instance fields) so concurrent requests don't corrupt data.
- **Prototype-into-singleton trap.** The singleton is created once, so its prototype dependency is injected once and then reused forever — effectively becoming a singleton. Fixes: inject `ObjectProvider<T>` and call `getObject()` each time, use `@Lookup` method injection, or use a scoped proxy.

```java
@Service
public class ReportService {
    private final ObjectProvider<ReportBuilder> builders; // prototype bean

    public ReportService(ObjectProvider<ReportBuilder> builders) {
        this.builders = builders;
    }

    public Report generate() {
        ReportBuilder b = builders.getObject(); // fresh instance each call
        return b.build();
    }
}
```

**Follow-ups**

- *Does Spring call* `@PreDestroy` *on prototype beans?* No — the container hands them off and doesn't manage their destruction.
- *How do you inject a request-scoped bean into a singleton?* `@Scope(value = "request", proxyMode = ScopedProxyMode.TARGET_CLASS)` — the singleton holds a proxy that resolves the real bean per request.
- *Are singletons eager or lazy?* Eager by default at startup; `@Lazy` defers creation to first use.



## Q4. Explain the Spring bean lifecycle. Where would you put initialization logic?

**Answer.** A singleton bean goes through these stages, in order:

1. **Instantiation** — constructor is called (constructor injection happens here).
2. **Populate properties** — setter/field injection.
3. **Aware callbacks** — `BeanNameAware`, `ApplicationContextAware`, etc.
4. `BeanPostProcessor.postProcessBeforeInitialization`
5. **Initialization** — `@PostConstruct` → `InitializingBean.afterPropertiesSet()` → custom `initMethod`.
6. `BeanPostProcessor.postProcessAfterInitialization` — *this is where AOP proxies are created*, so the bean you get injected may be a proxy, not the raw object.
7. **Bean in use.**
8. **Destruction** on context shutdown — `@PreDestroy` → `DisposableBean.destroy()` → custom `destroyMethod`.

For init logic I use `@PostConstruct`: by then all dependencies are injected, unlike in the constructor body where only constructor args exist. For cleanup (closing a pool, flushing a buffer) I use `@PreDestroy`.

If the logic needs the *whole* application ready — e.g. warming a Redis cache by calling other services — I use `ApplicationRunner` or `@EventListener(ApplicationReadyEvent.class)` instead, because `@PostConstruct` runs while the context is still being built.

```java
@Component
public class RateCardCache {
    private final RateRepository repo;
    private Map<String, BigDecimal> rates;

    public RateCardCache(RateRepository repo) { this.repo = repo; }

    @PostConstruct
    void load() { rates = repo.findAllAsMap(); }   // deps ready

    @PreDestroy
    void clear() { rates.clear(); }
}
```

**Follow-ups**

- *Why does step 6 matter?* It explains why `@Transactional`/`@Async` work only on the proxy (see Q10).
- *BeanPostProcessor vs BeanFactoryPostProcessor?* BFPP edits bean *definitions* before any bean is created (e.g. resolving `${}` placeholders); BPP wraps or edits bean *instances*.
- *Is* `@PostConstruct` *part of Spring?* It's Jakarta annotations (`jakarta.annotation`), supported by Spring.



## Q5. @Component vs @Bean — when do you use which? How do @Service, @Repository, @Controller differ?

**Answer.** Both register a bean; the difference is *who writes the class*.

- `@Component` goes on a class I own. Spring discovers it through component scanning.
- `@Bean` goes on a method inside a `@Configuration` class. I use it for classes I *can't* annotate (third-party: `RestTemplate`, `ObjectMapper`, `KafkaTemplate`, AWS `S3Client`) or when construction needs logic — timeouts, conditional choice, multiple configured instances of one type.

The stereotypes are all `@Component` underneath, but they carry intent and some behaviour:


| Annotation        | Layer           | Extra behaviour                                                                 |
| ----------------- | --------------- | ------------------------------------------------------------------------------- |
| `@Service`        | Business logic  | None today — pure semantics                                                     |
| `@Repository`     | Data access     | Persistence exceptions translated into Spring's `DataAccessException` hierarchy |
| `@Controller`     | Web (MVC views) | Handler methods mapped by DispatcherServlet                                     |
| `@RestController` | Web (REST)      | `@Controller` + `@ResponseBody` — return values serialized to JSON              |


```java
@Configuration
public class HttpClientConfig {
    @Bean
    public RestClient paymentClient(RestClient.Builder builder) {
        return builder.baseUrl("https://payments.internal")
                      .build();
    }
}
```

**Follow-ups**

- *What does* `@Configuration` *add over* `@Component` *for* `@Bean` *methods?* `@Configuration` classes are CGLIB-proxied, so calling one `@Bean` method from another returns the same singleton instead of creating a new object. In a plain `@Component` ("lite mode") each call creates a new instance. `proxyBeanMethods = false` turns this off for faster startup.
- *Default bean name?* Class name with lowercase first letter for `@Component`; the method name for `@Bean`.



## Q6. You have two implementations of the same interface. How does Spring decide which to inject?

**Answer.** By default Spring injects by type. With two candidates it fails at startup with `NoUniqueBeanDefinitionException`. I resolve it one of four ways:

1. `@Primary` on one implementation — the default choice when nothing else is specified.
2. `@Qualifier("name")` at the injection point — explicit choice; it overrides `@Primary`.
3. **Parameter name matching the bean name** — works as a fallback, but fragile, so I don't rely on it.
4. **Inject all of them** as `List<T>` or `Map<String, T>` — the Strategy pattern, which is what I use when the choice depends on runtime data.

Real example: a payment service supporting UPI, card and net-banking. Each gateway is a `@Component` implementing `PaymentGateway`; Spring injects a map keyed by bean name, and I pick at runtime. Adding a new gateway is a new class — no `if/else` change (Open/Closed principle).

```java
public interface PaymentGateway { PaymentResult pay(PaymentRequest r); }

@Component("UPI")  class UpiGateway  implements PaymentGateway { ... }
@Component("CARD") class CardGateway implements PaymentGateway { ... }

@Service
public class PaymentService {
    private final Map<String, PaymentGateway> gateways;

    public PaymentService(Map<String, PaymentGateway> gateways) {
        this.gateways = gateways;
    }

    public PaymentResult pay(PaymentRequest r) {
        PaymentGateway g = gateways.get(r.method());
        if (g == null) throw new UnsupportedPaymentMethodException(r.method());
        return g.pay(r);
    }
}
```

**Follow-ups**

- `@Primary` *vs* `@Qualifier` *— which wins?* `@Qualifier` at the injection point.
- *Ordering in a* `List<T>`*?* Controlled with `@Order` or implementing `Ordered`.
- *Optional dependency?* `ObjectProvider<T>.getIfAvailable()` or `Optional<T>`.



## Q7. What is a circular dependency? How does Spring Boot handle it, and how do you fix it?

**Answer.** A circular dependency is A needs B and B needs A (or a longer loop A → B → C → A).

- **With constructor injection** it can never be resolved: to build A you need a finished B, which needs a finished A. Spring fails at startup with `BeanCurrentlyInCreationException`.
- **With field/setter injection** Spring *used to* resolve it silently: it creates a half-built A, exposes an early reference through its three-level singleton cache, injects that into B, then finishes A.
- **Since Spring Boot 2.6**, circular references are **prohibited by default** even for field injection — the app fails with "The dependencies of some of the beans form a cycle." You can re-enable with `spring.main.allow-circular-references=true`, but that only hides the problem.

A cycle is a design problem, not a Spring problem. My fixes, in order of preference:

1. **Extract the shared logic** into a third bean C that both A and B depend on. This is the usual right answer.
2. **Use events** — A publishes an `ApplicationEvent`, B listens. The dependency becomes one-way.
3. **Rethink responsibilities** — often one service is calling "back up" into a caller it shouldn't know about.
4. `@Lazy` **on one injection point** — Spring injects a proxy that resolves later. A quick fix, but it hides the smell.

```java
// Before: OrderService <-> InventoryService
// After: both depend on StockReservationService, no cycle
@Service
class OrderService {
    OrderService(StockReservationService stock) { ... }
}
@Service
class InventoryService {
    InventoryService(StockReservationService stock) { ... }
}
```

**Follow-ups**

- *Why are constructor-injected cycles impossible to resolve?* No early reference exists until the constructor completes.
- *What's the three-level cache?* `singletonObjects` (finished), `earlySingletonObjects` (early refs), `singletonFactories` (factories that can produce an early ref, possibly a proxy).



## Q8. How do you manage configuration across dev, QA and prod? @Value vs @ConfigurationProperties?

**Answer.** Spring Boot builds one `Environment` from many property sources with a fixed precedence. The ones that matter, highest first:

1. Command-line args (`--server.port=9090`)
2. OS environment variables (`SPRING_DATASOURCE_URL`) — how we inject config in Kubernetes
3. Profile-specific files: `application-prod.yml`
4. Default `application.yml`

So I keep shared defaults in `application.yml`, environment overrides in `application-{profile}.yml`, and activate with `spring.profiles.active=prod` (usually an env var in the K8s deployment). **Secrets never go in the repo** — they come from env vars backed by Kubernetes Secrets, AWS Secrets Manager, or Vault.

`@Profile("dev")` on a bean registers it only in that profile — e.g. a stub payment client in dev.

`@Value` **vs** `@ConfigurationProperties`**:**


|                  | `@Value("${x}")`            | `@ConfigurationProperties(prefix)`                  |
| ---------------- | --------------------------- | --------------------------------------------------- |
| Best for         | One or two scattered values | A group of related settings                         |
| Type safety      | String-based, per field     | Strongly typed POJO/record                          |
| Validation       | Manual                      | `@Validated` + `@NotNull`, `@Min`                   |
| Relaxed binding  | Limited                     | `retry-count`, `RETRY_COUNT`, `retryCount` all bind |
| IDE autocomplete | No                          | Yes, with the config processor                      |


I default to `@ConfigurationProperties` with a Java record — immutable, validated at startup, so a bad config fails fast instead of at 2 a.m.

```java
@Validated
@ConfigurationProperties(prefix = "payment.client")
public record PaymentClientProps(
    @NotBlank String baseUrl,
    @Min(100) int connectTimeoutMs,
    @Min(0) int maxRetries) {}

// enable with @ConfigurationPropertiesScan on the main class
```

**Follow-ups**

- *Can a config change apply without restart?* Not by default. Spring Cloud Config + `@RefreshScope` + `/actuator/refresh` (or Spring Cloud Bus) can do it — covered in the Config Server topic.
- *Multiple active profiles?* Yes, comma-separated; later ones override earlier.



## Q9. Walk me through what happens when SpringApplication.run() is called.

**Answer.** At a level a 2 YOE engineer should know:

1. **Create** `SpringApplication` — detects the app type (Servlet, Reactive, or none) from the classpath, loads initializers and listeners.
2. **Prepare the** `Environment` — loads `application.yml`, profile files, env vars, command-line args; resolves active profiles.
3. **Print the banner, create the** `ApplicationContext` — `AnnotationConfigServletWebServerApplicationContext` for a web app.
4. **Load bean definitions** — component scanning plus auto-configuration imports; conditions are evaluated here. Nothing is instantiated yet.
5. `refresh()` — the heart of startup:
  - run `BeanFactoryPostProcessor`s (placeholder resolution, config-class parsing)
  - register `BeanPostProcessor`s
  - **create the embedded web server** (Tomcat by default)
  - **instantiate all non-lazy singletons** — DI, lifecycle callbacks, proxies (Q4)
  - **start Tomcat** on the configured port
6. **Call** `ApplicationRunner` **/** `CommandLineRunner` **beans.**
7. **Publish** `ApplicationReadyEvent` — the app is ready; the readiness probe can now go green.

Why it matters in practice: most startup failures map to one step. A missing bean or cycle fails in step 5; a bad property in step 2 or at binding; "port already in use" when Tomcat starts. In Kubernetes, slow step 5 (big context, eager cache warming) is why we tune the startup probe.

**Follow-ups**

- *How do you speed up startup?* Lazy initialization (`spring.main.lazy-initialization=true`, with care), trimming unused starters, `proxyBeanMethods = false`, CDS / AOT / GraalVM native image in Boot 3.
- *Fat jar — how does* `java -jar` *work?* `JarLauncher` in the jar's manifest builds a classloader over the nested `BOOT-INF/lib` jars, then calls your main class.



## Q10. How do Spring proxies work? Why doesn't @Transactional work when a method calls another method in the same class?

**Answer.** Annotations like `@Transactional`, `@Async`, `@Cacheable` and `@Retryable` are implemented with **AOP proxies**. During startup (lifecycle step 6), Spring wraps the bean in a proxy, and *that proxy* is what gets injected into other beans. A call from outside goes caller → proxy (begin transaction) → real method → proxy (commit/rollback).

Two proxy types:

- **JDK dynamic proxy** — implements the bean's interfaces; works only through the interface.
- **CGLIB proxy** — subclasses the bean class. **Spring Boot uses CGLIB by default** (`proxyTargetClass=true`).

**Self-invocation problem:** when `placeOrder()` calls `this.saveAudit()` inside the same class, the call goes through `this` — the raw object — and never passes the proxy. So `@Transactional(propagation = REQUIRES_NEW)` on `saveAudit()` is silently ignored.

The same proxy rule causes other silent failures:

- `private` methods — CGLIB can't override them, so the annotation does nothing.
- `final` methods or classes — can't be subclassed.
- Calling the method on an object you created with `new` — not a Spring bean, no proxy.

**Fixes:** move the method to a separate bean (cleanest), or inject the bean into itself with `@Lazy` / use `TransactionTemplate` for programmatic control.

```java
@Service
public class OrderService {
    private final AuditService audit; // separate bean → goes through proxy

    public OrderService(AuditService audit) { this.audit = audit; }

    @Transactional
    public void placeOrder(Order o) {
        // ... save order
        audit.record(o);   // REQUIRES_NEW now actually applies
    }
}

@Service
class AuditService {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Order o) { ... }
}
```

**Follow-ups**

- *Same problem with* `@Async`*?* Yes — a self-call runs synchronously on the same thread.
- *Does* `@Transactional` *roll back on checked exceptions?* No, only on `RuntimeException` and `Error` by default; use `rollbackFor = Exception.class`. (Deep dive in the Transactions topic.)
- *AOP terms?* Aspect (the module), advice (the code: `@Before`, `@Around`…), pointcut (where it applies), join point (a method execution).



## Rapid revision sheet

Read this 10 minutes before the interview.


| #   | Question                | One-line answer                                                                                      |
| --- | ----------------------- | ---------------------------------------------------------------------------------------------------- |
| 1   | IoC / DI                | Container builds and injects dependencies; prefer constructor injection (final, testable, fail-fast) |
| 2   | Auto-config             | `.imports` file lists configs; `@ConditionalOn*` decide; your own bean wins (back-off)               |
| 3   | Scopes                  | Singleton default and must be stateless; prototype-in-singleton → `ObjectProvider`                   |
| 4   | Lifecycle               | Construct → inject → Aware → BPP before → `@PostConstruct` → BPP after (proxy) → `@PreDestroy`       |
| 5   | `@Component` vs `@Bean` | Own class vs third-party/complex construction; `@Repository` adds exception translation              |
| 6   | Two impls               | `@Primary` default, `@Qualifier` explicit, `Map<String,T>` for runtime strategy                      |
| 7   | Circular deps           | Banned by default since Boot 2.6; extract a third bean or use events                                 |
| 8   | Config                  | CLI > env vars > profile yml > default yml; `@ConfigurationProperties` + `@Validated`                |
| 9   | Startup                 | Environment → context → bean definitions → `refresh()` (beans + Tomcat) → runners → ready event      |
| 10  | Proxies                 | CGLIB proxy wraps bean; self-calls, private, final bypass it                                         |


**Curveballs to prepare one line for**

- How would you write a custom Spring Boot starter?
- What is Spring Boot Actuator and which endpoints would you expose in prod?
- `@Controller` vs `@RestController`?
- What changed in Spring Boot 3 — Java 17 baseline, `javax` → `jakarta`, native image/AOT support, Micrometer Observation API.

