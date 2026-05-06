# Questionnaire — Booking Platform

Thirty questions total. The first twenty cover issues found in the code and how to fix them; the last ten cover production metrics that help identify performance problems in distributed systems.

---

## Part A — Issues and solutions (20 questions)

### user-service

**1. The `listUsers()` endpoint returns a list of users together with the number of bookings each one has. As traffic grows, the endpoint becomes very slow even though each individual SQL query is fast. What is happening, and how would you fix it?**

**Respuesta:** En `UserController.listUsers()` el código realiza un `findAll()` para cargar todos los usuarios y luego ejecuta una consulta `SELECT COUNT(*) FROM bookings WHERE user_id = ?` para cada usuario. Esto es un patrón N+1: el número total de consultas crece linealmente con la cantidad de usuarios. Bajo carga, la latencia total se dispara aunque cada consulta sea rápida.
- Se puede resolver con una sola consulta agregada con `LEFT JOIN` y `GROUP BY` para devolver `id`, `name`, `email` y `booking_count` en una sola llamada a la base de datos.
-Esto mejora el rendimiento y la escalabilidad porque el servicio no dispara miles de consultas por petición.

**2. The `searchUsers()` endpoint accepts a string `q` and runs `WHERE name LIKE '%q%' OR email LIKE '%q%'`. What two distinct problems does this code have, and how would you fix each?**

**Respuesta:** El código actual concatena `q` directamente en SQL, lo que permite inyección SQL y también fuerza un escaneo completo de índice porque el patrón empieza con `%`.
- Problema 1: seguridad / SQL injection. Se debe usar parámetros preparados o métodos de repositorio con binding de parámetros, no concatenación de cadenas.
- Problema 2: rendimiento. `LIKE '%...%'` no puede aprovechar un índice tradicional y escala mal. Para arreglarlo se puede usar búsqueda de texto completo o índices trigram en Postgres, o al menos normalizar y usar `ILIKE` con un índice adecuado.
- Corrección aplicada: cambiar `searchUsers()` para delegar a `UserRepository.searchByQuery(q)` con binding seguro y luego migrar a una solución de búsqueda con índices si el catálogo crece.

**3. The `getUserProfile()` endpoint caches the assembled profile in Redis with `redisTemplate.opsForValue().set(cacheKey, profile)`. Two issues are hidden in this single line for a system at scale. What are they?**

**Respuesta:** Esa línea esconde dos problemas graves:
1. Se usa `profile.toString()` implícitamente al guardar el objeto, lo que produce una representación no estructurada y no deserializable de forma fiable. No es un formato JSON portable, por lo que el caché no es realmente utilizable para reconstruir el perfil.
2. No se establece TTL ni expiración. Esto causa datos obsoletos y crecimiento indefinido de la caché, lo que degrada resiliencia y memoria en Redis.
- Corrección: serializar con JSON y establecer un tiempo de vida razonable (`Duration.ofMinutes(10)`), o usar `@Cacheable` con configuración de expiración e invalidación en actualizaciones.

**4. `application.yml` has `spring.datasource.hikari.maximum-pool-size: 5`. Under load you observe many requests waiting and timing out at the controller layer even though the database itself is barely loaded. Why, and how would you size the pool properly?**

**Respuesta:** Un pool de conexiones de 5 está muy por debajo de la concurrencia de la aplicación. Cuando llegan más de cinco solicitudes simultáneas que necesitan DB, las peticiones se bloquean esperando conexión, aunque el propio PostgreSQL no esté saturado. El cuello de botella está en el pool, no en la base de datos.
- Corrección: dimensionar el pool desde la primera línea de uso: `poolSize ≈ appThreads × dbWaitRatio / (1 - dbWaitRatio)` o usar reglas prácticas como `maxPoolSize = max(threads, expectedConcurrentRequests)` tras medir `hikaricp_connections_pending`.
- Por lo que se aumenta el pool a `10` y se añade `minimum-idle: 5`, pero lo correcto es validar con métricas de saturación y latencia.

**5. The user controller has no global exception handler. What goes wrong with that in production, and how do you fix it?**

**Respuesta:** Sin un `@ControllerAdvice` global, las excepciones no controladas generan respuestas 500 inconsistentes, posibles fugas de stack traces y mala experiencia de cliente. También dificulta la instrumentación y el logging uniforme de errores.
- Corrección: agregar un handler global con `@ControllerAdvice` y `@ExceptionHandler(Exception.class)` para devolver un JSON estándar de error y preservar el contrato de la API. Esto mejora la resiliencia y hace que los fallos sean predecibles.

### booking-service

**6. `createBooking()` is annotated/declared `synchronized` *and* uses an internal `bookingLock`. What is wrong with this design, and how should concurrency for booking creation actually be handled?**

**7. `listBookings()` returns each booking enriched with the user and the room. With many bookings, the endpoint becomes very slow. What is the problem and what are two ways to fix it?**

**8. `RestTemplate` is registered as a `@Bean` with no explicit configuration. In production this leads to outages that look like the booking-service is "stuck". Explain why, and what configuration is needed.**

**9. The `POST /bookings` endpoint has no idempotency mechanism. Why is this dangerous in a microservice context, and how do you implement idempotency correctly?**

**10. In `createBooking()`, the call to `notification-service` happens synchronously *after* the booking is committed. What two failure modes does this cause, and what is the standard fix?**

**11. The booking flow has no circuit breaker on the calls to `inventory-service` or `user-service`. Describe the failure mode this enables, and what a circuit breaker actually does.**

### inventory-service

**12. `listRooms()` is reported as "the slowest endpoint in the system" — sometimes 30+ seconds. Inspecting the code reveals two compounding problems. What are they?**

**13. `searchRooms()` calls its own `/rooms/{id}/availability` endpoint via HTTP for every room in the catalog. What is wrong with this approach, and what should it do instead?**

**14. `computeDynamicPrice()` returns `BigDecimal.ZERO` when the room is not found. Why is this a resilience problem and not just a correctness bug? What is the right pattern?**

**15. The dynamic-pricing computation is repeated identically across many concurrent requests during a sale. Why is this a scalability problem, and what is the correct caching strategy for a value that "changes slowly"?**

### notification-service

**16. The `notify()` endpoint retries failed sends up to five times with `Thread.sleep(2000)` between attempts, all in the request thread. Describe both the local impact and the upstream impact.**

**17. The retry loop uses a fixed 2-second delay. Even if it were moved off the request thread, this is still wrong. Why, and what is the correct algorithm?**

**18. `broadcast()` sends a message to every user by iterating in a `for` loop and inserting one row per recipient. Identify three distinct issues and how you would address them.**

**19. Failed notifications are stored with `status = "failed"` and forgotten. Why is this a silent reliability bug, and how should a production system handle delivery failures?**

**20. The whole notification path runs synchronously inside the HTTP request triggered by `booking-service`. What architectural change makes the system both more resilient and more scalable, and what new concerns does it introduce?**

---

## Part B — Performance metrics for production diagnosis (10 questions)

**21. *Latency percentiles (p50 / p95 / p99) per endpoint.* What does it mean when p50 is healthy (e.g. 80 ms) but p99 is 5 s, and what kinds of root causes does that pattern point to?**

**22. *Throughput (requests per second) and saturation point.* How do you experimentally determine the maximum throughput of a service, and what should you watch for as the indicator that you have crossed it?**

**23. *Error rate, broken down by status class (4xx vs 5xx).* Why is this distinction critical, and how do you set actionable alert thresholds?**

**24. *Database connection-pool saturation (`hikaricp_connections_pending`, `hikaricp_connections_active`).* In this project, several services have `maximum-pool-size: 5`. What metric tells you the pool is the bottleneck, and what does a non-zero `pending` value mean?**

**25. *Service-to-service call latency, separated from internal work.* In a microservice, total request time = local work + sum of downstream call times. Why must you instrument these separately, and how does distributed tracing make this possible?**

**26. *JVM heap, GC pause time, and GC frequency.* What pattern in these metrics indicates a memory leak, and what indicates that GC is itself becoming a performance problem?**

**27. *Thread-pool saturation (Tomcat busy threads, executor queue depth).* In a Spring Boot service, what metric tells you the HTTP layer is the bottleneck, and how does it interact with downstream call latency?**

**28. *Cache hit ratio.* The `getUserProfile()` endpoint caches in Redis. If the hit ratio is 30 %, what does that tell you, and what are the most common causes of a low hit ratio?**

**29. *Apdex / SLO compliance.* Instead of arguing about "is 800 ms fast enough", how do you turn user experience into a single number you can track, and what is the difference between an SLI, an SLO, and an Apdex score?**

**30. *Saturation, the fourth USE/RED metric.* Beyond Latency, Errors, and Throughput (RED), why is "saturation" — how full a resource is — the single best leading indicator that performance is about to degrade, and what concrete things would you measure on each service in this project?**
