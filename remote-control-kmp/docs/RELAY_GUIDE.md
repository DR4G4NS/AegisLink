# Guía del relay

El relay permite que Android y Windows se registren, descubran y autoricen una
sesión cuando no comparten la red local. No es un proxy SSH ni un servidor de
video.

## Límite de confianza

- El relay transporta registro, solicitud, aprobación, eventos y señalización.
- WebRTC mueve video y DataChannels por una ruta directa o por TURN.
- El relay no debe recibir contraseñas, claves privadas, secretos permanentes
  del QR ni contenido SSH/SFTP.
- La aprobación visible abre el rendezvous, pero no sustituye E2EE.
- Input y clipboard remotos permanecen deshabilitados hasta completar E2EE de
  aplicación. HTTPS/WSS y DTLS no sustituyen ese requisito.
- El código de E2EE/rekey existe; la matriz física integrada sigue abierta.

## Prueba de desarrollo en Windows

Desde `remote-control-kmp`:

```powershell
.\gradlew.bat :relay-server:relay-main:test :relay-server:relay-main:installDist

$env:PORT='8080'
$env:AEGIS_RELAY_DEVELOPMENT_MODE='true'
$env:AEGIS_RELAY_TOKEN_HMAC_SECRET='base64url:<al-menos-32-bytes-aleatorios>'
$env:AEGIS_RELAY_ORIGIN='http://192.168.1.20:8080'
$env:AEGIS_RELAY_STORAGE_PATH="$PWD\data\relay-registry.json"
$env:AEGIS_RELAY_RATE_LIMIT_STORAGE_PATH="$PWD\data\relay-rate-limits.json"
.\relay-server\relay-main\build\install\relay-main\bin\relay-main.bat
```

No uses el valor de ejemplo del secreto fuera de una prueba. Desde el teléfono,
`127.0.0.1` apunta al propio Android; usa la IP privada del PC. No abras el
puerto del router para esta prueba.

Comprobaciones:

```powershell
Invoke-RestMethod http://127.0.0.1:8080/health
Invoke-RestMethod http://127.0.0.1:8080/live
Invoke-RestMethod http://127.0.0.1:8080/ready
```

`/metrics` expone métricas Prometheus y no debe publicar secretos.

## Registro y sesión

1. Configura la misma URL base del relay en Windows y Android.
2. Windows registra su identidad mediante challenge/proof y anuncia acceso
   remoto solo cuando el usuario activa ese control.
3. Android registra su identidad y solicita una sesión al ID relay del PC.
4. Windows muestra la identidad del teléfono y aprueba o rechaza.
5. Los peers completan E2EE de aplicación.
6. Solo después negocian WebRTC y habilitan tráfico remoto sensible.
7. ICE elige ruta directa o TURN.

Los IDs relay son localizadores, no identidades criptográficas. No intercambies
el ID del teléfono con el del PC ni lo uses como fingerprint.

La rotación de identidad usa un `operationId` firmado y durable. Si se pierde
la respuesta HTTP, el mismo origen puede reintentar de forma idempotente con la
clave anterior registrada; el cliente no activa la clave nueva hasta persistir
la confirmación remota. Si falta el token efímero, la recuperación exige un
challenge nuevo y prueba de la identidad persistida; el localizador por sí solo
nunca recupera propiedad.

## Despliegue durable

Para un despliegue persistente configura PostgreSQL y Redis juntos:

```powershell
$env:AEGIS_DATABASE_URL='jdbc:postgresql://db.example:5432/aegis'
$env:AEGIS_DATABASE_USER='aegis'
$env:AEGIS_DATABASE_PASSWORD='<gestor-de-secretos>'
$env:AEGIS_REDIS_URL='rediss://redis.example:6379/0'
$env:AEGIS_RELAY_TOKEN_HMAC_SECRET='base64url:<al-menos-32-bytes-aleatorios>'
$env:AEGIS_RELAY_ORIGIN='https://relay.example.com'
```

Requisitos operativos:

- proxy inverso HTTPS/WSS con WebSocket;
- `AEGIS_TRUST_FORWARDED_HEADERS=true` solo si el relay es accesible
  exclusivamente a través del proxy confiable;
- secretos desde un gestor, rotación, copias de seguridad y límites de abuso;
- no registrar encabezados de autorización, tokens ni URLs WebSocket completas;
- readiness, métricas, alertas, cuotas y retención de datos documentadas.

El modo memoria/JSON es exclusivamente de desarrollo y exige
`AEGIS_RELAY_DEVELOPMENT_MODE=true`.

En modo durable, Redis también arbitra presencia y exclusividad entre réplicas.
Cada participante de señalización y cada suscriptor de eventos adquiere un lease
con ID de conexión; claim, renovación, comprobación de propiedad y liberación
son atómicos. Una réplica antigua no puede liberar el lease o borrar la
presencia de una conexión nueva. La pérdida de lease cierra el socket con
`REL-7414` o `REL-7415` en vez de aceptar participantes duplicados.

## TURN opcional

```powershell
$env:AEGIS_TURN_URLS='turn:turn.example.com:3478?transport=udp,turns:turn.example.com:5349?transport=tcp'
$env:AEGIS_TURN_SHARED_SECRET='<mismo-secreto-rest-que-coturn>'
$env:AEGIS_TURN_TTL_SECONDS='600'
```

TURN aumenta latencia, ancho de banda y costo. No transporta SSH/SFTP. La
validación RC debe incluir una asignación autenticada real y una sesión
TURN-only; una credencial emitida o un puerto abierto no demuestra el flujo.

## Diagnóstico

| Síntoma | Comprobación |
| --- | --- |
| El proceso no inicia | Verifica secreto HMAC y que PostgreSQL/Redis estén ambos configurados o el modo desarrollo esté explícito |
| Android no registra | Confirma URL/origen, hora, challenge/proof, certificado y conectividad desde el teléfono |
| El PC no aparece | Confirma ID relay exacto, conexión de eventos y acceso remoto anunciado |
| La solicitud no llega | Revisa WebSocket de eventos, proxy, expiración, rate limit y logs causales |
| Se aprueba pero no controla | Comprueba primero E2EE; aprobación no habilita input/clipboard por sí sola |
| ICE directo falla | Revisa candidate pair y configura TURN solo si está correctamente desplegado |
| Funciona en LAN pero no Internet | Usa HTTPS público válido; no publiques SSH ni abras puertos del router |
| Respuesta 429 | Espera la ventana y busca clientes duplicados o reintentos agresivos |

Conserva en evidencia solo IDs sanitizados, códigos causales, timestamps y ruta
seleccionada. Nunca adjuntes tokens, claves, contraseñas, capacidades QR ni
plaintext E2EE.
