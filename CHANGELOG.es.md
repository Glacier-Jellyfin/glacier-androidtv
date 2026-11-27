# Changelog (Español)

The Spanish release notes, shown in the app when its language is Spanish.
Every version section mirrors the one in [CHANGELOG.md](CHANGELOG.md), with
these subsections in this order: `### Novedades`, `### Mejoras`,
`### Correcciones`, `### Problemas conocidos`. Versions before 0.8.2 exist
only in English.

## [Unreleased]

### Novedades
- Una fila de Grabaciones en la pantalla de inicio y las grabaciones como fuente opcional del destacado
- Una fila de Grabaciones en la página de favoritos
- Detalles de fotos y vídeos en el visor de fotos. Con Abajo o la tecla Info, la primera pestaña muestra cuándo y dónde se tomó y su carpeta, y la pestaña Técnico la cámara, la exposición y el archivo. Un vídeo muestra las mismas pestañas Técnico y Servidor que el reproductor. Mantén pulsado OK o pulsa Menú para abrir las opciones, que también eliminan una foto o un vídeo del servidor tras una confirmación cuando el servidor lo permite. También funciona manteniendo pulsado OK sobre una foto o un vídeo en la carpeta (#43). Un ajuste rellena las barras negras alrededor de las fotos y los vídeos verticales con una copia desenfocada
- Las páginas de detalles de películas y series con varias imágenes de fondo las muestran una tras otra

### Mejoras
- La línea sobre un título ahora es una ruta. Sus niveles abren la biblioteca, la serie, la temporada o una carpeta superior
- Los especiales que una temporada muestra entre sus episodios llevan la etiqueta «Especial»
- Las etiquetas sobre imágenes de episodios y pósteres tienen un fondo más oscuro y se leen bien en imágenes claras
- Los detalles del episodio llaman a un especial «Especiales» en lugar de temporada 0
- La elección de audio y subtítulos durante la reproducción ya no muestra «Se aplica ahora» bajo el título
- Al ordenar por año, los títulos del mismo año siguen su fecha de estreno en lugar del orden alfabético
- Al subir o bajar a una fila, ahora se empieza en su primera tarjeta. Después la fila recuerda la tarjeta en la que la dejaste, también al volver de una página de detalles
- El orden de la A a la Z ignora artículos como «The», «Der» o «La» en el idioma de cada título. El idioma viene de tu servidor Jellyfin. En Ajustes › Apariencia › Ignorar artículos lo desactivas
- Al pasar fotos, el nombre y la fecha de la siguiente aparecen al instante, con una vista previa desenfocada y un indicador de carga hasta que la foto está nítida. Las siguientes fotos se cargan por adelantado, así que pasar fotos hace esperar menos. Una foto que no carga se puede reintentar con OK (#43)
- El visor de fotos muestra sus teclas como teclas dibujadas en la esquina inferior al abrirse, y con Arriba vuelven a aparecer (#43)
- Los vídeos de una carpeta de fotos llevan el símbolo de reproducción, y los clips de menos de un minuto muestran su duración en segundos (#43)
- Tras pasar a un flujo más pequeño, la reproducción vuelve mucho antes a la calidad completa: en unos 40 segundos en lugar de varios minutos, con menos espera antes del cambio
- El panel de información muestra cuándo se hace la próxima comprobación de la conexión mientras se reproduce un flujo más pequeño
- Los vídeos caseros sin imagen en el servidor muestran un fotograma del vídeo en la cuadrícula, la galería y el visor. Los vídeos verticales y los grabados en vertical con el móvil aparecen en la orientación correcta

### Correcciones
- Las fotos y los vídeos muestran la hora en que se tomaron en la zona horaria del televisor. Antes se desviaba por la diferencia con UTC (#43)
- La temporada de especiales muestra sus episodios en orden y no por título
- Los vídeos caseros girados se ven derechos también en televisores que ignoraban el giro (#43)
- Las grabaciones de TV en directo ya no aparecen entre las películas y series. Siguen en TV en directo > Grabaciones y la búsqueda las sigue encontrando
- Los tráileres de YouTube ya no se quedan en la pantalla de carga ni fallan cuando ya se están reproduciendo
- La reproducción ya no pasa a un flujo más pequeño por una sola prueba de conexión lenta mientras sigue al día. La prueba comparte la conexión con el vídeo, así que se repite y se contrasta antes con la velocidad del reproductor
- Un episodio ya no empieza con subtítulos (o audio) que el servidor eligió para un episodio anterior, como subtítulos en japonés. El siguiente episodio solo adopta las pistas que elegiste tú
- Los vídeos caseros ya no muestran la cuenta atrás «Siguiente episodio» cerca del final. El siguiente vídeo de la carpeta sigue empezando cuando termina uno

## [0.10.1] - 2026-11-18

### Mejoras
- La reproducción funciona también con conexiones lentas de 1 Mbit/s o menos: la prueba de velocidad las mide bien, las imágenes pequeñas se ajustan a la conexión y el audio también es pequeño

### Correcciones
- La página de detalles y el reproductor ya no se cierran con una pista de idioma desconocido
- Un flujo DTS dañado ya no detiene la reproducción con audio passthrough
- Con la reproducción en pausa vuelve a iniciarse el salvapantallas del televisor, en lugar de solo atenuarse la pantalla

## [0.10.0] - 2026-11-13

### Novedades
- Una temporada se puede marcar como vista o no vista (mantén pulsado OK en la temporada), y las temporadas vistas muestran una marca

### Mejoras
- Los menús de opciones se abren como un panel lateral. La página sigue a la vista y cada opción explica lo que hace
- La aplicación se puede mover al almacenamiento externo en los dispositivos que lo admiten
- La reproducción mide la conexión y adapta la transcodificación a ella, para que no se entrecorte con una conexión más lenta
- Cuando cambia la conexión, la reproducción pasa sin interrupciones entre el archivo e imágenes más pequeñas (4K, 1080p, 720p)
- El vídeo transcodificado crea un búfer grande y se entrecorta mucho menos
- La transcodificación se genera para el tamaño de la pantalla
- Un servidor lento ya no termina la reproducción con un error
- Nuevo diseño del diálogo de errores de reproducción
- Con una conexión demasiado lenta para un archivo, el servidor convierte solo el sonido y mantiene la imagen donde cabe
- Reanudar un título empieza más rápido y puede comenzar unos segundos antes del punto guardado
- El panel de información muestra un cambio de transmisión próximo y su motivo
- La cuenta atrás para saltar y para el siguiente episodio avanza con fluidez
- El panel de información de una película o un episodio muestra el reparto con fotos y papeles en lugar del capítulo y el siguiente episodio, que el reproductor ya muestra
- El panel de información de una canción pone la canción en primer plano: álbum, año, pista y género, cuántas veces y cuándo se escuchó por última vez, y el artista con foto y biografía. «A continuación» y la cola, que el reproductor ya muestra, desaparecen. Un videoclip muestra su imagen, su duración y cuántas veces se vio
- El panel de información de TV en directo muestra la programación del canal como una línea con los próximos programas en lugar de las tarjetas de ahora y a continuación
- Atrás en las pestañas de una biblioteca, de TV en directo o de carpetas lleva directamente a la pantalla de inicio, donde el foco se queda en la barra de navegación en lugar de saltar a la página cuando termina de cargar
- Atrás en las filas de la pantalla de inicio vuelve arriba
- Las bibliotecas y TV en directo conservan sus filtros (corazón, no vistos) y el orden, también tras reiniciar. Hasta cambiar de perfil vuelven además a la última pestaña o vista, carpeta y título usados

### Correcciones
- «Subtítulos desactivados» elegido en la página de detalles también se aplica al siguiente episodio
- «A continuación» espera mientras aún sigue una escena tras los créditos
- Al marcar una serie como vista o no vista, los episodios y las temporadas se actualizan al instante
- Mientras algo se carga o se conecta, el mando ya no mueve el foco a botones que quedan detrás de la pantalla de carga, y un segundo OK ya no inicia sesión dos veces
- El foco ya no salta a otro sitio si ya te has movido mientras una página termina de cargar
- Atrás en el salvapantallas de música solo cierra el salvapantallas, la página de detrás se queda como estaba

## [0.9.0] - 2026-11-05

### Novedades
- Interfaz en español

### Mejoras
- Dolby Vision perfil 7 de archivos MKV de doble capa se reproduce en Dolby Vision
- El sonido enviado al receptor vuelve a empezar solo cuando el televisor deja de reproducirlo
- Los cortes breves de HDMI ya no pasan el sonido del receptor al dispositivo
- Ajustes › Sistema abre el aviso de actualización, donde las notas largas de la versión se leen hasta el final

### Correcciones
- Algunos archivos MKV con Dolby Vision ya no se detienen con un error de lectura
