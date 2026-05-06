# Booking Platform — Sample Project with Performance, Scalability, and Resilience Issues



## Architecture

| Service | Port | Responsibility |
|---|---|---|
| `user-service` | 5001 | User accounts, profiles, preferences |
| `booking-service` | 5002 | Create / cancel / list bookings |
| `inventory-service` | 5003 | Rooms, availability, dynamic pricing |
| `notification-service` | 5004 | Send confirmations, cancellations, broadcasts |

Stack: Spring Boot 3.2 · Java 17 · PostgreSQL 15 · Redis 7 · Docker Compose.

```
client ──► booking-service ──► user-service
                          ├──► inventory-service
                          └──► notification-service ──► user-service
```

## Run it

```bash
docker compose up --build
```

---

## Issues by service

> Numbering: **P** = Performance, **S** = Scalability, **R** = Resilience.

---

### 1. user-service

**File:** `user-service/src/main/java/com/booking/user/controller/UserController.java`

#### Performance

- **P1** N+1 Query Problem en `listUsers()`: Ejecuta `findAll()` + 1 query adicional por cada usuario (`SELECT COUNT(*) FROM bookings WHERE user_id = ?`). Con 10k usuarios = 10,001 queries, causando latencia alta (P95/P99), alto IO Wait y saturación de conexiones BD. Impacto en Throughput.

- **P2** Múltiples queries secuenciales en `getUserProfile()`: Ejecuta 5 queries sin paralelización (user + preferences + bookings + reviews + loyalty). Cada query es un roundtrip a la BD. Latencia total es suma de todas. Alto P95/P99.

- **P3** Hash MD5 ineficiente en `createUser()`: Usa `MessageDigest.getInstance("MD5")` + StringBuilder con `String.format()` por cada byte. MD5 es criptográficamente débil y lento. CPU intensivo innecesariamente. Throughput bajo en creación de usuarios.

- **P4** Sin paginación en `listUsers()`: `findAll()` carga TODOS los usuarios en memoria de una sola vez. 100k usuarios = ~50-100 MB en RAM + GC Collection pauses. Aumenta latencia al recolectar basura.

#### Scalability

- **S1** N+1 Queries no escalan horizontalmente: Con 10,001 queries por request, cada instancia agota el pool de conexiones BD (por defecto 10). No escala vertical. Cluster de 10 servidores × 10k usuarios = imposible. Bottleneck en BD.

- **S2** Redis cache sin TTL en `getUserProfile()`: `redisTemplate.opsForValue().set(cacheKey, profile.toString())` sin expiración. Datos se quedan cachés indefinidamente. Memoria Redis crece sin límite. No es escalable con millones de usuarios activos.

- **S3** Sin paginación en endpoints críticos: `listUsers()` y `searchUsers()` sin límites ni offset. No pueden manejar crecimiento del dataset. Con 1M usuarios, endpoint colapsa (OOM, timeout).

- **S4** SQL Injection en `/search`: `"SELECT * FROM users WHERE name LIKE '%" + q + "%'..."` concatena directamente el input. Bajo carga de ataques, puede ejecutar queries destructivas. Requiere validación y prepared statements.

#### Resilience

- **R1** Sin manejo de excepciones en queries: Si BD está down o timeout, endpoints lanzan excepciones no controladas → 500 error. Sin retry logic ni circuit breaker. Sin fallback a datos cached.

- **R2** Dependencia dura de Redis en `getUserProfile()`: Si Redis está down, el try-catch de `redisTemplate.opsForValue().get()` falla sin fallback. Endpoint completo falla. Sin circuit breaker. Sin timeout configurado.

- **R3** Sin timeout en queries JDBC: `jdbcTemplate.queryForObject()` y `queryForList()` sin timeout configurado. Query lenta o stuck puede bloquear thread indefinidamente. Causa thread pool exhaustion.

- **R4** Sin validación de inputs ni autorización: `@PathVariable Long id` sin validación por ejemplo: usuario accede a perfil de otro usuario. Sin Rate Limiting. Sin manejo de errores específicos (404, 403, 400). Vulnerable a abuso.
---

### 2. booking-service

**File:** `booking-service/src/main/java/com/booking/booking/controller/BookingController.java`

#### Performance

- **P1** `createBooking()` es `synchronized`: Bloquea TODA la creación de bookings con un único lock global (`bookingLock`). Cada request espera por el anterior (serialización). Throughput muy bajo. Con 100 requests/s → latencia P99 = 100s. CPU baja, threads bloqueados esperando.

- **P2** Llamadas HTTP síncronas sin timeout en `createBooking()`: `restTemplate.getForEntity()` a user-service e inventory-service sin timeout. Si un servicio tarda 5s, el thread espera bloqueado 5s. Cada thread ocupa ~1MB. Alto P95/P99.

- **P3** N+1 HTTP calls en `listBookings()`: Por cada booking en la lista, hace 2 llamadas HTTP sincrónicas (user-service + inventory-service). 100 bookings = 200 HTTP calls secuenciales. Latencia total = suma de todos. Alto IO Wait, bajo throughput.

- **P4** Conversión de tipos sin validación en `createBooking()`: `Long.valueOf()`, `LocalDate.parse()` sin try-catch previo. Si fallan lanzan excepciones, causando GC stress y desperdicio de CPU. Sin logging de errores.

#### Scalability

- **S1** `synchronized` method no escala horizontalmente: Un solo lock global serializa todas las creaciones. Cluster de 10 servidores = 1 servidor realmente activo. Los otros 9 esperando. No hay paralelización. Imposible escalar.

- **S2** Llamadas HTTP bloqueantes sin pool management: Cada request bloqueante a otro servicio ocupa 1 thread del pool. Con 200 hilos y latencia 1s cada request = max 200 req/s. Si inventory-service es lento (5s), max 40 req/s. Sin async/reactive → no escala.

- **S3** Sin circuit breaker en llamadas entre servicios: Si inventory-service está down, booking-service queda colgado esperando. Todos los threads se agotan. Cascada de fallos. Sin fallback.

- **S4** Sin paginación en `listBookings()`: Sin parámetros `limit`/`offset`. Puede traer 1M bookings → OOM, alto GC. 2M bookings × 2 HTTP calls = 4M operaciones síncronas.

#### Resilience

- **R1** Llamadas HTTP sin retry logic: Si user-service falla momentáneamente, endpoint falla inmediatamente. Sin reintentos. Sin fallback. Sin timeout configurado en RestTemplate.

- **R2** Silencio de excepciones en `listBookings()`: `catch (Exception ignored) {}` ignora errores de user-service/inventory-service silenciosamente. Usuario recibe bookings sin datos del usuario/room. Sin logging. Difícil de debuggear.

- **R3** Sin timeout en RestTemplate: `restTemplate.getForEntity()` puede quedarse esperando indefinidamente si el otro servicio no responde. Causa thread pool exhaustion. Sin mecanismo de fallback.

- **R4** Sin validación de entrada y manejo de errores específicos: `Long.valueOf()` puede lanzar NumberFormatException. Sin validación de fechas (checkOut < checkIn). Sin 400 Bad Request específico. Sin rate limiting en endpoint.

---

### 3. inventory-service

**File:** `inventory-service/src/main/java/com/booking/inventory/controller/RoomController.java`

#### Performance

- **P1** `Thread.sleep(500)` en `computeDynamicPrice()`: Método artificial bloquea thread 500ms por CADA cálculo de precio dinámico. CPU desperdiciado. Latencia P95 = 500ms+ por request. Throughput muy bajo. Llamado en `listRooms()` por cada sala.

- **P2** N+1 queries en `computeDynamicPrice()`: Por cada sala, ejecuta 2 queries adicionales (bookings + avg price). Con 1000 salas = 3000 queries totales. `listRooms()` itera sobre todas las salas llamando a `computeDynamicPrice()`. Latencia total = 1000 × 500ms = 500 segundos. Alto IO Wait.

- **P3** Sin paginación en `listRooms()` y `searchRooms()`: `findByIsActiveTrue()` carga TODAS las salas en memoria de una vez. 100k salas = 100MB+ en RAM + iteración en Java + GC pauses. Bajo throughput, alto P99.

- **P4** HTTP call circular en `searchRooms()`: Llama a `restTemplate.getForEntity()` a `http://inventory-service:5003/rooms/{id}/availability` desde dentro de inventory-service. Thread bloqueado esperando respuesta de sí mismo. Latencia suma de todas las llamadas HTTP. Bajo throughput.

#### Scalability

- **S1** `Thread.sleep(500)` no escala: Cada request ocupa 1 thread por 500ms. Con 200 threads pool = max 400 req/s. Pero sumado a queries = max 20-40 req/s. Imposible escalar horizontal.

- **S2** N+1 queries con `computeDynamicPrice()` agota BD: 1000 salas × 3 queries = 3000 conexiones BD por request. Pool por defecto = 10. Timeout, agotamiento de conexiones. No escala vertical.

- **S3** Sin paginación = no puede crecer dataset: Si dataset crece de 1000 a 100k salas, endpoint colapsa (OOM). Búsqueda lineal sobre toda la lista en memoria. Scaling imposible.

- **S4** HTTP call circular recursivo: Llamada desde `searchRooms()` a sí mismo genera cascada de requests. Bajo cluster, causa thundering herd. Thread pool agotado. Imposible escalar.

#### Resilience

- **R1** `Thread.sleep()` hardcoded sin timeout/retry: Si network es lento, desastre. No hay retry logic ni circuit breaker. Se queda esperando 500ms siempre.

- **R2** Excepciones silenciadas en `searchRooms()`: `catch (Exception ignored) {}` esconde errores. Si inventory-service no responde a sí mismo, salas simplemente desaparecen de resultados. Sin logging.

- **R3** Sin timeout en RestTemplate en `searchRooms()`: `restTemplate.getForEntity()` puede quedar esperando indefinidamente si hay red slow/drops. Causa thread pool exhaustion. Sin fallback a datos cached.

- **R4** Cache Redis sin TTL en `getRoom()`: `redisTemplate.opsForValue().set(cacheKey, room.get().toString())` sin expiración. Datos obsoletos indefinidamente. Si Redis down, no hay fallback a BD. Sin circuit breaker.

---

### 4. notification-service

**File:** `notification-service/src/main/java/com/booking/notification/controller/NotificationController.java`

#### Performance

- **P1** `Thread.sleep(2000)` en retry loop en `notify()`: Si primer envío falla, duerme 2 segundos × 5 intentos = 10 segundos máximo. Thread desperdiciado sin hacer trabajo. Latencia P95/P99 muy alta. Bajo throughput en notificaciones.

- **P2** Llamada HTTP síncrona a user-service sin timeout en `notify()`: `restTemplate.getForObject()` a `user-service:5001/users/{userId}` sin timeout ni configuración. Si user-service tarda 5s, thread bloqueado 5s. Alto P95.

- **P3** Llamada HTTP síncrona a SMTP gateway sin timeout: `restTemplate.getForEntity()` a `smtp-gateway:8080/send` sin timeout. Si SMTP es lento (10s), thread bloqueado 10s × 5 reintentos = 50s máximo por notificación. Muy bajo throughput.

- **P4** `broadcast()` es sincrónico sobre TODOS los usuarios: `findAll()` + iteración + `sendEmail()` por cada usuario. 100k usuarios = 100k HTTP calls secuenciales. Latencia total = suma de todas. Imposible de completar en tiempo razonable. Alto CPU, bajo throughput.

#### Scalability

- **S1** `Thread.sleep(2000)` en retry no escala: Cada retry ocupa 1 thread por 2 segundos. Con 200 threads pool = max 100 notificaciones/s. Cluster de 10 servidores = 1000 notificaciones/s. Bajo carga pico.

- **S2** Retry logic sin exponential backoff ni circuit breaker: `Thread.sleep(2000)` siempre igual. Si SMTP gateway está down, todos los threads esperan 10 segundos. Cascada de fallos. Sin fallback.

- **S3** `broadcast()` sincrónico no escala: 100k usuarios × 1s por notificación = 100k segundos (28 horas) en 1 servidor. No hay paralelización, no hay async/queue. Imposible manejar broadcasts en plataforma grande.

- **S4** Sin paginación en `listNotifications()` sin userId: `findAll()` trae TODAS las notificaciones. Si hay 1M notificaciones = OOM. Datos acumulativos sin límite. No es escalable.

#### Resilience

- **R1** Retry logic sin exponencial backoff: `Thread.sleep(2000)` siempre igual. Sin jitter, sin circuit breaker, sin timeout acumulativo. Si SMTP está down, solo desperdicia 10 segundos y falla.

- **R2** Sin timeout configurado en RestTemplate: `restTemplate.getForObject()` puede quedar indefinidamente esperando si network drops o timeout. Causa thread pool exhaustion. Sin fallback a datos cached.

- **R3** Excepciones silenciadas en `sendEmail()`: `catch (Exception e) { return false; }` no loguea errores. Sin retry específico para SMTP. Sin circuit breaker para SMTP gateway.

- **R4** Sin validación de inputs ni autorización en `/notify` y `/notify/broadcast`: No valida `userId`, `type`, `message`. No hay autenticación, solo `@PostMapping`. Vulnerable a spam/abuse masivo. Sin rate limiting.

