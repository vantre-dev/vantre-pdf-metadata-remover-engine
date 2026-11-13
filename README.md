# Vantre PDF Engine

The on-device engine behind [PDF Metadata Remover](https://getvantre.com/products/pdf-metadata-remover/) for Android. It reads and removes hidden metadata from PDF files and from Word, Excel and PowerPoint documents: author and title fields, creation and edit dates, producer and creator, XMP packets, annotations, bookmarks, embedded JavaScript, layers, form data, comments, tracked changes and speaker notes.

Everything runs on the device. The engine has no network code and no INTERNET permission. Read the source to check.

## What it does

- `PdfMetadataExtractor.extract()` audits a PDF and returns a `PdfMetadata` with every field it found.
- `PdfMetadataScrubber.scrub()` writes a cleaned PDF copy and reports a `ScrubOutcome` (success, needs password, wrong password or failure).
- `OfficeDocuments.extract()` and `OfficeDocuments.scrub()` do the same for `.docx`, `.xlsx` and `.pptx` files. `OfficeInspector` and `OfficeCleaner` work on plain `File` objects, so they also run in JVM unit tests.
- `DocumentFormat.detect()` identifies the format from the file name, MIME type and file contents.

## Verify it yourself

1. Take a PDF or Office file that carries an author, dates and a producer string.
2. Run it through the scrubber.
3. Inspect the output with `exiftool`, `pdfinfo` or the [browser viewer](https://getvantre.com/tools/pdf-metadata-viewer/). The identifying fields should be gone.

`OfficeCleanerTest` shows the idea on a sample document with a planted author, e-mail address and revision history.

## Build and test

```
./gradlew :engine:testDebugUnitTest
```

Needs JDK 17+ and the Android SDK (API 36).

## Using it

It is an Android library module (`minSdk 26`). Add `:engine` to your project, or copy the module. The package is `dev.striptools.strippdf`. PDF handling uses [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android).

## Contributing

Bug reports are welcome, especially a file that still leaks metadata after cleaning. Pull requests are reviewed on a best-effort basis.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

Made by [Vantre](https://getvantre.com).
