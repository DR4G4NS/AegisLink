# Acceso remoto

Actualiza Aegis en Windows con el instalador de esta versión y actualiza la app
del teléfono. Mantén Aegis abierto en el PC: cerrar la ventana o elegir **Salir**
corta pantalla, terminal y archivos. **Ocultar** desde el icono de la bandeja deja
la app funcionando y el PC debe seguir apareciendo conectado.

## Conexión sencilla

1. Vincula el teléfono mediante el QR de Aegis, con ambos equipos en el mismo Wi-Fi.
2. Instala [Tailscale](https://tailscale.com/docs/how-to/connect-to-devices) en el
   PC y el teléfono. Inicia sesión en la misma cuenta y actívalo en ambos.
3. En Android, abre el PC vinculado → **Acceso remoto** y copia la dirección del
   PC que muestra Tailscale. Pulsa **Guardar y comprobar**.
4. Apaga el Wi-Fi del teléfono y prueba con datos móviles. Abre la pantalla,
   terminal y archivos para comprobar las funciones que tengas permitidas.

No hace falta configurar un servidor ni abrir puertos en el router. Tailscale
conecta los dispositivos directamente cuando la red lo permite y dispone de una
conexión alternativa cuando no puede hacerlo. Véanse sus
[tipos de conexión](https://tailscale.com/docs/reference/connection-types) y
[requisitos del cortafuegos](https://tailscale.com/docs/reference/faq/firewall-ports).

## Usar la IP pública del router

1. Vincula primero el teléfono con el QR dentro de tu red.
2. Reserva una dirección local para el PC en el router, para que no cambie.
3. En **Reenvío de puertos**, crea estas reglas hacia esa dirección:

   | Uso | Protocolo | Puerto externo | Puerto del PC |
   | --- | --- | --- | --- |
   | Conexión y pantalla | TCP | 48291 | 48291 |
   | Terminal y archivos | TCP | 48222 | 48222 |

4. En **Acceso remoto** escribe la IP pública de tu router o un nombre que se
   actualice cuando esa IP cambie. Si elegiste otros puertos externos, introdúcelos
   en **Cambiar puertos**. No cambies los puertos de destino de la tabla.
5. Prueba con datos móviles. El botón de comprobación verifica la identidad del
   PC y sus permisos; cada función se comprueba al abrirla.

El PC debe permitir Aegis en su red de confianza: el instalador crea las reglas
para los perfiles **Privado** y **Dominio** de Windows. Si la red de tu casa está
marcada como **Pública**, cambia su perfil a **Privado** en Configuración de red.

Si tu proveedor comparte la dirección pública entre varios hogares, el reenvío
del router no será suficiente. Solicita una IP pública al proveedor o utiliza
**Conexión sencilla**. Si conecta pero no muestra la pantalla, usa también esa
opción: el video negocia su propia conexión y algunos routers no la permiten.
La opción de IP pública no garantiza video en todas las redes.

## Permisos por teléfono

En Windows, abre **Dispositivos**, selecciona el teléfono y guarda los permisos
que quieras conceder: ver pantalla, usar ratón y teclado, compartir texto,
terminal y archivos, o encender el equipo. Al quitar la pantalla también se quita
su control. Encender depende de que el equipo y la red lo admitan.

Terminal y archivos comparten un permiso porque una terminal también permite
leer y modificar archivos. Cambiar ese permiso corta las conexiones de terminal
y archivos que estén abiertas; los teléfonos que conserven permiso pueden volver
a conectarse. Los demás cambios reinician las sesiones del teléfono afectado.

## Actualizar una instalación anterior de Windows

Hace falta ejecutar el instalador actualizado: copiar únicamente el ejecutable
no cambia la configuración del servicio instalado. El instalador conserva las
claves y la vinculación y aplica el acceso ligado a la app. Si aparece el error
`SSH-7334`, vuelve a ejecutar ese instalador.

Para diagnóstico: el servicio administrado escucha exclusivamente en
`127.0.0.1:48222`; Aegis abre las entradas de red mientras está activo. Cerrar el
proceso elimina esas entradas y corta ambos extremos de las conexiones. Esto
no modifica otros servicios SSH que el usuario tenga instalados por su cuenta.
