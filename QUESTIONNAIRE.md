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

**Respuesta:** El método `createBooking()` está marcado como `synchronized` y usa un `bookingLock` interno, lo que serializa todas las solicitudes de creación de bookings, causando un cuello de botella en concurrencia. Esto no escala porque solo una petición puede procesarse a la vez. La concurrencia real debe manejarse en la base de datos con constraints únicos o locking optimista, permitiendo que múltiples hilos procesen bookings concurrentemente sin bloqueos globales.

**7. `listBookings()` returns each booking enriched with the user and the room. With many bookings, the endpoint becomes very slow. What is the problem and what are two ways to fix it?**

**Respuesta:** El problema es N+1 queries: por cada booking, se hace una llamada HTTP a `user-service` y `inventory-service` para enriquecer los datos. Con muchos bookings, esto causa latencia alta y carga en servicios downstream. Dos formas de arreglarlo: 
1) Devolver solo IDs en la respuesta y dejar que el cliente haga las llamadas necesarias (mejora la escalabilidad al distribuir carga) implementamos esta solución. 
2) Usar batch APIs o caching para reducir llamadas HTTP.

**8. `RestTemplate` is registered as a `@Bean` with no explicit configuration. In production this leads to outages that look like the booking-service is "stuck". Explain why, and what configuration is needed.**

**Respuesta:** `RestTemplate` por defecto no tiene timeouts configurados, por lo que llamadas HTTP pueden bloquearse indefinidamente si el servicio remoto no responde, causando que el hilo se quede esperando y agotando el pool de hilos (P). Esto lleva a "stuck" porque no hay liberación de recursos. Se necesita configurar `connectTimeout` y `readTimeout` (ej. 5s y 10s) para evitar hangs y mejorar resiliencia (R).

**9. The `POST /bookings` endpoint has no idempotency mechanism. Why is this dangerous in a microservice context, and how do you implement idempotency correctly?**

**Respuesta:** Sin idempotency, reintentos de red o fallos temporales pueden crear bookings duplicados, causando inconsistencia de datos. En microservicios, los clientes reintentan automáticamente, lo que es peligroso para operaciones no idempotentes. Implementar con una clave de idempotency (ej. UUID en header), almacenada en DB, verificando existencia antes de procesar.

**10. In `createBooking()`, the call to `notification-service` happens synchronously *after* the booking is committed. What two failure modes does this cause, and what is the standard fix?**

**Respuesta:** Dos modos de falla: 
1) Si la notificación falla, el booking se hace pero el usuario no se entera (pérdida de notificación). 
2) Si se hace rollback por falla en notificación, se pierde el booking válido. 
- La solución estándar es usar messaging asíncrono (ej. RabbitMQ o Kafka) para enviar notificaciones después del commit, desacoplando y mejorando resiliencia.

**11. The booking flow has no circuit breaker on the calls to `inventory-service` or `user-service`. Describe the failure mode this enables, and what a circuit breaker actually does.**

**Respuesta:** Sin circuit breaker, si `user-service` o `inventory-service` fallan, `booking-service` continúa intentando llamadas, causando cascada de fallos, agotamiento de recursos y downtime (R). Un circuit breaker (ej. Resilience4j) monitorea fallos y abre el circuito temporalmente, fallando rápido y permitiendo recuperación, previniendo cascadas.

### inventory-service

**12. `listRooms()` is reported as "the slowest endpoint in the system" — sometimes 30+ seconds. Inspecting the code reveals two compounding problems. What are they?**

**Respuesta:** En `RoomController.listRooms()` la lentitud viene de dos problemas compuestos:
1) `computeDynamicPrice()` se invoca dentro del loop por cada habitación, lo que dispara consultas adicionales por room. Esto multiplica la carga y genera una O(N) excesiva sobre la base de datos.
2) `computeDynamicPrice()` incluye un `Thread.sleep(500)`, lo que agrega 500 ms de latencia por habitación y convierte solicitudes de listado en caminos de ejecución extremadamente lentos. Ambos juntos degradan el rendimiento (P) y hacen que el endpoint no escale (S).

**13. `searchRooms()` calls its own `/rooms/{id}/availability` endpoint via HTTP for every room in the catalog. What is wrong with this approach, and what should it do instead?**

**Respuesta:** Ese diseño hace un HTTP loopback interno dentro del mismo servicio: por cada habitación activa se dispara una llamada a `inventory-service` para verificar disponibilidad. Esto es ineficiente, frágil y crea latencia y uso innecesario de recursos. En lugar de eso, debe usar directamente la lógica interna de disponibilidad o una consulta de base de datos compartida, sin pasar por la red. Para esto usamos `isRoomAvailable()` directo para arreglarlo.

**14. `computeDynamicPrice()` returns `BigDecimal.ZERO` when the room is not found. Why is this a resilience problem and not just a correctness bug? What is the right pattern?**

**Respuesta:** Devolver `BigDecimal.ZERO` oculta un error de estado: una habitación inexistente se convierte en un precio válido de `0`, lo cual puede propagar datos incorrectos hacia el cliente y hacia otros servicios. Esto no es solo un bug de exactitud; es una falla de resiliencia porque un recurso faltante produce una respuesta aparentemente válida que puede desencadenar decisiones erróneas downstream. La forma correcta es devolver un error explícito (`404`) o lanzar una excepción controlada, no un valor de negocio falso.

**15. The dynamic-pricing computation is repeated identically across many concurrent requests during a sale. Why is this a scalability problem, and what is the correct caching strategy for a value that "changes slowly"?**

**Respuesta:** Calcular el precio dinámico idénticamente en múltiples solicitudes genera trabajo redundante y carga innecesaria en la base de datos. Durante picos de demanda, esto puede saturar la capa de datos y aumentar latencias. La estrategia correcta es cachear el precio dinámico por llave de contexto (por ejemplo `roomId` + `checkIn` + `checkOut`) con TTL razonable, de modo que se reutilice el valor mientras siga siendo válido. Así se mejora la escalabilidad y se reduce la presión sobre la base de datos en ventas con alto tráfico.

### notification-service

**16. The `notify()` endpoint retries failed sends up to five times with `Thread.sleep(2000)` between attempts, all in the request thread. Describe both the local impact and the upstream impact.**

**Respuesta:** Los reintentos con `Thread.sleep(2000)` en el hilo de la request bloquean el hilo Tomcat. Con 5 intentos x 2 segundos = 10 segundos de bloqueo por fallo, lo que agota el pool de threads (local impact en P). Upstream, `booking-service` espera a que `notify()` complete; si muchas notificaciones fallan, se acumula carga de espera causando timeouts en cascada y agotamiento de threads en booking-service también degradando la escalabilidad y resiliencia.

**17. The retry loop uses a fixed 2-second delay. Even if it were moved off the request thread, this is still wrong. Why, and what is the correct algorithm?**

**Respuesta:** Un delay fijo de 2 segundos es predecible: si el servidor de emails está saturado, todos los reintentos llegan exactamente cada 2 segundos, amplificando la carga (problema de "thundering herd"). La solución correcta es exponential backoff con jitter: esperar 2s, luego 4s, luego 8s, etc., con variación aleatoria. Esto espacía los reintentos, reduce picos de carga y evita ciclos de congestión.

**18. `broadcast()` sends a message to every user by iterating in a `for` loop and inserting one row per recipient. Identify three distinct issues and how you would address them.**

**Respuesta:** Tres problemas:
1. **Loop de inserciones individuales**: Por cada usuario, una INSERT separada. Esto es O(N) lento y genera mucha overhead de red a la BD. Solución: usar `saveAll()` o batch insert.
2. **Bloqueo en el request thread**: El loop completo se ejecuta dentro de la HTTP request, bloqueando al cliente. Solución: usar `@Async` para ejecutar en thread pool separado.
3. **Sin manejo de fallos**: Si `user-service` falla a mitad del broadcast, se pierden notificaciones. Solución: capturar excepciones y registrar fallos en dead-letter queue para reintentos.

**19. Failed notifications are stored with `status = "failed"` and forgotten. Why is this a silent reliability bug, and how should a production system handle delivery failures?**

**Respuesta:** Marcar un status como "failed" sin mecanismo de reintento o alertas es un bug silencioso: notificaciones críticas (confirmación de booking, etc.) nunca llegan pero nadie se da cuenta. Esto degrada la resiliencia. 
La solución correcta es: 
1) Almacenar con `status = "dead_letter"` tras agotar reintentos. 
2) Implementar un scheduler que reintente mensajes pendientes con exponential backoff. 
3) Monitorear la cola de dead-letter con alertas para intervención manual.

**20. The whole notification path runs synchronously inside the HTTP request triggered by `booking-service`. What architectural change makes the system both more resilient and more scalable, and what new concerns does it introduce?**

**Respuesta:** El cambio arquitectónico es desacoplar notificaciones usando messaging asincrónico (RabbitMQ, Kafka, SQS). En lugar de `booking-service` llamar a `notify()` directamente, publica un evento a una cola. `notification-service` consume asincronamente, intentando reintentos sin bloquear a booking-service. Mejora la escalabilidad (desacoplamiento) y resiliencia (fallos aislados). 
Nuevas preocupaciones: 
1) Consistencia eventual (notificaciones llegan después), 
2) Complexity operacional (monitorear colas), 
3) Necesidad de dead-letter queues y alertas para fallos persistentes, 
4) Duplicate processing si el consumer falla (se necesita idempotency).

---

## Part B — Performance metrics for production diagnosis (10 questions)

**21. *Latency percentiles (p50 / p95 / p99) per endpoint.* What does it mean when p50 is healthy (e.g. 80 ms) but p99 is 5 s, and what kinds of root causes does that pattern point to?**

**Respuesta:** Un p50 saludable (80 ms) pero p99 alto (5 s) indica tail latencies causadas por eventos raros. 
Root causes: 
1) GC pauses que congelan threads (100ms+). 
2) Thread pool saturation ocasional. 
3) Lock contention en BD. 
4) Connection pool exhaustion con pool-size=5. 

En el proyecto se encuentra N+1 queries en listUsers() e inventory-service/listRooms() causan picos de latencia. 

**22. *Throughput (requests per second) and saturation point.* How do you experimentally determine the maximum throughput of a service, and what should you watch for as the indicator that you have crossed it?**

**Respuesta:** Mediante load testing: incrementar RPS gradualmente (10, 50, 100, 500, 1000) observando métricas. Saturation point es donde latencia y error rate degradan. 

Indicadores de cruce: 
1) p99 latency sube de 200ms a 2000ms+. 
2) Error rate > 0 (primeras 5xx responses). 
3) hikaricp_connections_pending > 0 (pool exhausted). 
4) tomcat_threads_busy = máximo. 
5) Throughput no crece pero latency crece. 
En el proyecto esta con pool-size=5 y N+1 queries, saturation point es bajo (~100-200 RPS).

**23. *Error rate, broken down by status class (4xx vs 5xx).* Why is this distinction critical, and how do you set actionable alert thresholds?**

**Respuesta:** 4xx = error del cliente (bad request), 5xx = error del servidor (culpa infraestructura). Crítico porque alertar en ambos causa falsos positivos. 
En el proyecto 4xx es esperable (bad bookings, invalid users), 5xx nunca debería ocurrir. 

Thresholds accionables: 
1) 5xx > 1% = CRITICAL alert (degradación). 
2) 4xx > 10% = WARNING (patrón anómalo, posible bot). 
3) 5xx > 0.1% pero estable = investigate. 

Podemos alertar si 409 (conflict) sube porque indica contención de reservas, o 408 (timeout) sube porque indica latencia upstream.

**24. *Database connection-pool saturation (`hikaricp_connections_pending`, `hikaricp_connections_active`).* In this project, several services have `maximum-pool-size: 5`. What metric tells you the pool is the bottleneck, and what does a non-zero `pending` value mean?**

**Respuesta:** Pool es bottleneck cuando connections_active = 5 (máximo) sostenido AND connections_pending > 0. Un pending no-zero es crítico: solicitudes se bloquean esperando conexión, acumulan en cola, degradan latencia y eventualmente timeout. En el proyecto con pool-size=5: bajo carga normal pending=0, pero en picos (varias listBookings + listRooms concurrentes) pending sube rápidamente porque N+1 queries agota todas las conexiones. Alerta: if pending > 0 for > 5 segundos = ALERT. 

Solución: 
1) Aumentar pool-size a 10-15. 
2) Eliminar N+1 queries. 
3) Agregar caching. La métrica clave es hikaricp_connections_pending; si > 0, el pool es el cuello de botella, no la base de datos.

**25. *Service-to-service call latency, separated from internal work.* In a microservice, total request time = local work + sum of downstream call times. Why must you instrument these separately, and how does distributed tracing make this possible?**

**Respuesta:** Sin separación, no sabes dónde está el problema real. Por ejemplo tenemos booking-service/listBookings toma 3 segundos. Esto porque: 

 - local processing lento (100ms).
 - llamadas HTTP a user-service lentas (2s). 
- Sin instrumentación por separado, adivinas. 

Con separación: local_time = total - downstream_time. Si local_time = 100ms pero downstream = 2.9s, el problema es remoto. Distributed tracing (OpenTelemetry, Jaeger) hace esto propagando trace ID entre servicios: 
1) booking-service genera trace ID. 
2) Lo añade en header X-Trace-ID. 
3) user-service recibe y propaga la misma trace ID. 
4) inventory-service igual. 
5) Jaeger agrupa todos los spans bajo una trace. 

Por lo que en el proyecto: booking-service/listBookings hace N llamadas HTTP. Con tracing ves: booking-service (100ms) → user-service (2s) → inventory-service (3s). Conclusión inmediata: inventory-service es el problema. Herramientas: OpenTelemetry + Jaeger, o Spring Cloud Sleuth + Zipkin.

**26. *JVM heap, GC pause time, and GC frequency.* What pattern in these metrics indicates a memory leak, and what indicates that GC is itself becoming a performance problem?**

**Respuesta:** Patrón de memory leak: heap usage crece monotónicamente (100MB → 200MB → 500MB → 800MB acercándose al máximo). Tras cada GC, recovery es mínimo (<5%). Indica objetos no liberados acumulándose. En este proyecto: si Redis cache en getUserProfile() nunca expira (no había TTL en original), o si notificaciones acumulan en memoria sin limpieza, heap crece indefinidamente. 
GC se convierte en performance problem cuando: 
1) GC frequency > 1 pausa/segundo (indica presión de heap). 
2) GC pause time > 100ms (stop-the-world bloquea todos los threads). 
3) GC time % de total > 5% (mucho tiempo reciclando, poco tiempo útil). 

Patrón típico: heap lleno → GC frecuente → pausas largas → latencia aumenta. 
 
 Métrica de alerta clave: si jvm_gc_pause_seconds > 0.1s frecuentemente, degradación está próxima.

**27. *Thread-pool saturation (Tomcat busy threads, executor queue depth).* In a Spring Boot service, what metric tells you the HTTP layer is the bottleneck, and how does it interact with downstream call latency?**

**Respuesta:** HTTP layer es bottleneck cuando tomcat_threads_busy = tomcat_threads_current (todos los threads ocupados) Y nuevas solicitudes se encolan. La métrica clave es **tomcat_threads_busy / tomcat_threads_current ratio**, que debe estar < 0.7 en producción. Si ratio = 1.0 sostenido, el layer está saturado. 

Interacción con downstream call latency: si una solicitud hace llamada HTTP lenta a otro servicio, el thread se bloquea esperando respuesta. Si muchas solicitudes hacen esto (ej. booking-service esperando user-service), todos los threads se bloquean, la queue se llena, y nuevas solicitudes reciben 503 (Service Unavailable). 

En este proyecto: booking-service/listBookings hace 2 llamadas HTTP por booking. Si user-service es lento (2 segundos), el thread de booking-service se bloquea 2s por booking. Con 50 bookings concurrentes = 50 threads bloqueados. Default Tomcat thread pool es ~10 threads, así que se satura rápidamente. 
Tendremos alerta si tomcat_threads_queue_size > 0 for > 10 segundos = HTTP layer saturado.

**28. *Cache hit ratio.* The `getUserProfile()` endpoint caches in Redis. If the hit ratio is 30 %, what does that tell you, and what are the most common causes of a low hit ratio?**

**Respuesta:** Hit ratio del 30% significa que de 10 solicitudes, 3 vienen de cache y 7 van a BD. Esto es muy bajo; en producción se espera 80%+ para que el caching sea efectivo. 

Causas comunes: 
1) **TTL too short**: en el código original no había TTL, en la corrección se añadió 10 minutos. Si un usuario hace request cada 12 minutos, siempre cache miss. 
2) **Working set > cache size**: si tenemos 1M usuarios activos y cache solo 100K, ratio baja. 
3) **Cache invalidation problems**: si el cache no se invalida cuando el perfil se actualiza, hay inconsistencia. 
4) **Cache key granularity**: en este proyecto, key = `user_profile_{id}`. Si cada usuario accede una sola vez, hit ratio = 0%. 


Métrica recomendada: `redis_cache_hits / (redis_cache_hits + redis_cache_misses)` por endpoint. Si getUserProfile hit ratio < 50%, investigar si TTL es muy corta o working set es mayor que cache. Nota: hit ratio bajo no siempre es malo si el trabajo remoto (BD) es rápido; importa más la p99 latency general.

**29. *Apdex / SLO compliance.* Instead of arguing about "is 800 ms fast enough", how do you turn user experience into a single number you can track, and what is the difference between an SLI, an SLO, and an Apdex score?**

**Respuesta:** Apdex (Application Performance Index) convierte latencias en un score 0-1. Define 2 thresholds: T (satisfactory, ej. 500ms) y 4T (tolerable, ej. 2s). Fórmula: Apdex = (satisfactory + tolerable/2) / total requests. Si p50=100ms, p90=400ms, p99=2s: Apdex ≈ 0.85 (bueno). 

Diferencias clave: 
1) **SLI** (Service Level Indicator): métrica observable (ej. "p99 latency" o "error rate"). 
2) **SLO** (Service Level Objective): target deseado (ej. "p99 < 500ms" o "error rate < 0.1%"). 
3) **Apdex**: índice de utilidad que resume user experience (0.8+ = satisfied, 0.5-0.8 = tolerated, <0.5 = frustrated). En este proyecto: definir SLI = listBookings p99 < 2s, SLO = Apdex > 0.8. 
Monitorear compliance: si Apdex cae bajo 0.7 por 5 minutos, trigger alert. Beneficio: en lugar de argumento abstracto "¿es 800ms fast?", stakeholders entienden "Apdex 0.7 significa 30% de usuarios frustrated". Herramientas: Prometheus alert en Apdex, Grafana dashboard mostrando SLI vs SLO.

**30. *Saturation, the fourth USE/RED metric.* Beyond Latency, Errors, and Throughput (RED), why is "saturation" — how full a resource is — the single best leading indicator that performance is about to degrade, and what concrete things would you measure on each service in this project?**

**Respuesta:** Saturation es el mejor leading indicator porque predice degradación antes de que ocurra. 

Patrón causal: saturation sube → solicitudes esperan → latency sube → error rate sube. Ejemplo: hikaricp_connections_pending > 0 (saturation) → p99 latency sube → usuarios reciben timeout. RED (Latency, Errors, Throughput) es reactivo: ves el problema cuando ya ocurrió. Saturation es proactivo: ves que el sistema se llena ANTES de fallar. USE metrics (Utilization, Saturation, Errors) son complementarias: Utilization = % de recurso en uso (connections_active / max), Saturation = items esperando (connections_pending), Errors = fallos. 

En este proyecto, medir por servicio: 
1) **user-service**: hikaricp_connections_active, hikaricp_connections_pending, tomcat_threads_busy, tomcat_threads_queue_size. 
2) **booking-service**: idem + redis memory usage. 
3) **inventory-service**: idem + redis key count (saturation de cache). 
4) **notification-service**: async_executor_active_threads, async_executor_queue_size (saturation de async task queue). 

Alertas preventivas: 
1) if hikaricp_connections_pending > 0 for 30 segundos → scale up BD o optimize queries. 
2) if tomcat_threads_queue_size > 5 → scale up threads o fix downstream latency. 
3) if async_executor_queue_size > 100 → notifications backing up, increase thread pool. Estas métricas dan 5-10 minutos de warning antes de que error rate suba."
