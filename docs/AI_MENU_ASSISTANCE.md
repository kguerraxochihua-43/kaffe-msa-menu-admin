# Creación asistida de menú

## Contrato

`POST /api/menu-admin/cafeterias/{tenantId}/ai-menu-drafts` admite:

- JSON: `idempotencyKey`, `text` (hasta 12000 caracteres).
- Multipart: `idempotencyKey`, `text` opcional, `image` opcional y `audio` opcional.
- Al menos una fuente es obligatoria. Se pueden combinar las tres.
- Imagen JPEG/PNG/WebP entre 1 KB y 5 MB. Service normaliza a JPEG de hasta 2.5 MB.
- Audio WAV PCM16 mono a 16 kHz, finalizado, entre 0.25 y 90 segundos. Se validan
  encabezado, longitud real, frecuencia, canales y profundidad; no basta la extensión.
- Multipart total máximo 6 MB. Imagen más audio no pueden superar 5700000 bytes.

Todas las operaciones requieren acceso global `menu:write` al comercio. El tenant
de la ruta prevalece: no se acepta otro tenant ni identificadores de catálogo en
el JSON producido por IA. Los límites existentes son 5 solicitudes por minuto y
30 por día por usuario; el proveedor limita a 2 llamadas simultáneas por instancia.

## Reconocimiento y JSON

El audio pasa primero por el modelo pequeño de transcripción configurado en
`kaffe.menu.ai.transcription-model`; el texto resultante y las demás fuentes pasan
a la extracción con el modelo existente `kaffe.menu.ai.model`. No se cambia a
modelos grandes. Credenciales exclusivamente en backend, configuración existente
desde AWS; no hay llamadas OpenAI desde Flutter.

La extracción usa Responses, `store:false`, sin herramientas y JSON Schema
estricto (`kaffe_menu_assisted_v2`). La respuesta se valida de nuevo en Java.
Fuentes oficiales: [Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs),
[Speech to text](https://developers.openai.com/api/docs/guides/speech-to-text).

Ejemplo de producto ficticio dentro de una categoría:

```json
{
  "name": "Latte",
  "description": null,
  "basePriceMinor": 6000,
  "priceText": "$60 / $75",
  "sortOrder": 0,
  "needsReview": false,
  "reviewReasons": [],
  "optionGroups": [{
    "name": "Tamaño",
    "required": true,
    "minSelection": 1,
    "maxSelection": 1,
    "options": [
      {"name": "Chico", "priceMinor": 0, "isDefault": false},
      {"name": "Grande", "priceMinor": 1500, "isDefault": false}
    ]
  }]
}
```

Los precios son centavos enteros. Se rechazan cadenas, fracciones, negativos y
desbordamientos; `null` significa pendiente, no gratuito. Los adicionales son
incrementos sobre la base. No se deducen ingredientes, alérgenos ni opciones que
no estén en la fuente. Una coincidencia incierta exige revisión humana; el prompt
no constituye garantía de exactitud y siempre se muestra un borrador editable.

## Guardado y seguridad

- `PATCH /{draftId}` conserva `expectedVersion`. Un conflicto no sobrescribe el
  borrador ajeno. Service conserva cambios locales si falla y permite recargar
  la versión guardada sólo tras confirmación.
- `POST /{draftId}/publish` con `createSeparateMenu:true` crea un menú **inactivo**,
  categorías nuevas asociadas exclusivamente a éste, productos y opciones.
  `publication.menuId` identifica el resultado. El estado histórico `published`
  del expediente significa transferido al catálogo, no activado para consumidores.
- La activación, sucursales y horarios se revisan en la administración existente.
- Si el nombre del menú ya existe, se solicita renombrar el borrador. Los grupos
  de opciones con nombre ocupado reciben el nombre del producto y, si hace falta,
  un número; nunca se reutilizan ni se alteran grupos existentes con otros precios.
- Clientes anteriores que no envían esa bandera conservan su publicación general.
- La transacción y el bloqueo del borrador impiden publicaciones duplicadas y
  revierten la creación completa si falla cualquier producto u opción.
- Se reutiliza la misma clave de creación tras un timeout. La clave queda ligada
  al actor, tenant y hash del contenido; no permite recuperar datos de otro comercio.
- Foto y audio no se guardan en base ni almacenamiento propio. Sí se conserva el
  texto de origen y el JSON para revisión por usuarios autorizados del comercio.
- No hay HTTP externo dentro de transacciones. Cada respuesta del proveedor tiene
  deadline completo, límite de 2 MB y no sigue redirects. El JSON almacenado tiene
  límite preventivo de 400 KB. No se registran fuentes ni claves en logs.

## Verificación

`mvn verify` cubre normalización, transporte, esquema, WAV, timeouts y proveedor
simulado. `AiMenuDraftPostgresTest` requiere una base **local y desechable** cuyo
nombre empiece por `kaffe_menu_test_`; prueba permisos, tenant, idempotencia,
conflictos y rollback transaccional. CI ejecuta también estas pruebas en Postgres.
No se usan comercios reales ni créditos de OpenAI en las pruebas automatizadas.
