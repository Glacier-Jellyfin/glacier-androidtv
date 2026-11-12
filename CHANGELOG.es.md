# Changelog (Español)

The Spanish release notes, shown in the app when its language is Spanish.
Every version section mirrors the one in [CHANGELOG.md](CHANGELOG.md), with
these subsections in this order: `### Novedades`, `### Mejoras`,
`### Correcciones`, `### Problemas conocidos`. Versions before 0.8.2 exist
only in English.

## [Unreleased]

### Mejoras
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

### Correcciones
- «Subtítulos desactivados» elegido en la página de detalles también se aplica al siguiente episodio
- «A continuación» espera mientras aún sigue una escena tras los créditos

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
