# ROMRenamer

An Android app that renames a folder of ROM files to the official No-Intro / Redump naming
standard. It hashes every file, looks the hash up in a DAT you supply, shows you exactly
what it intends to do, and only renames once you confirm.

Matching is by content, not by file name — a file called `smw.smc` is recognised as
`Super Mario World (USA).sfc` because its CRC32 says so.

## How it works

1. **Grant a folder.** The app asks for a directory tree through the Storage Access
   Framework. It declares no storage permissions and can only see the folder you pick.
2. **Load your DATs.** Pick individual Logiqx-style XML DATs from No-Intro or Redump, or
   point at a folder and every DAT inside it — at any depth — is parsed and merged into one
   database. A mixed ROM library then matches against every system at once, with no need to
   work out which DAT covers which file. Files in the folder that are not DATs are reported
   and skipped, never fatal.
3. **Scan.** Every file is read once and hashed off the main thread. Files whose size
   appears in no DAT entry are skipped without being read at all.
4. **Review.** A list shows current name, official name, and match status for each file.
   Nothing on disk has changed yet.
5. **Rename.** Ticked rows are renamed in place with `DocumentFile.renameTo`. Files are
   never moved, copied, or deleted.

## Project layout

```
app/src/main/java/com/romrenamer/app/
├── MainActivity.kt
├── core/
│   ├── storage/     SafHandler      — the directory grant and the recursive walk
│   ├── dat/         DatParser       — streaming XML parser for No-Intro / Redump DATs
│   │                DatIndex        — hash → game index, mergeable across DATs
│   │                DatLoader       — merges a whole selection into one index
│   ├── hash/        HashEngine      — CRC32 / MD5 / SHA-1 on coroutines, zip-aware
│   ├── match/       RomMatcher      — hash lookup and collision resolution
│   ├── rename/      BatchRenamer    — collision-checked, ordered rename execution
│   │                RomNaming       — official name derivation and sanitisation
│   └── scan/        RomScanner      — orchestrates walk → hash → match, streams results
└── ui/              MainScreen, MainViewModel, theme/
```

`core` has no dependency on `ui`, and everything except `storage`, `hash`, and `rename`
is free of Android framework types, which is what lets the parser and matcher be unit
tested on the JVM.

## Design notes

**Why CRC32 first.** Every DAT publishes CRC32 and it is the cheapest hash to compute. It
is also only 32 bits, so a single CRC hit is not always conclusive: DATs legitimately list
the same bytes under several releases, and true collisions exist. When a CRC lookup is
ambiguous, `RomMatcher` reports which stronger hashes would separate the candidates and
`RomScanner` re-reads the file just for those. Candidates that agree on the file name are
not treated as ambiguous — there is nothing for the user to decide.

**Why the index holds one hash map.** Merging a folder of DATs produces hundreds of
thousands of entries, where a map per algorithm would triple the memory for no benefit.
Only CRC32 is indexed; MD5 and SHA-1 are checked directly against the short candidate list
a CRC lookup returns. The fallback path — indexing by SHA-1 or MD5 — exists for the rare
DAT that publishes no CRC32 at all. `DatLoader` also stops merging at a fixed entry ceiling
and says so, rather than letting a full No-Intro collection exhaust the heap.

**Why a bad DAT is not fatal.** A folder selection will contain files that are not DATs.
Each file's failure is recorded against its source and the load continues, and the app can
show exactly which files contributed and which were passed over — a silently ignored DAT
would otherwise look identical to a ROM that genuinely is not catalogued. `DatParser`
stages a file's games and commits them only on a clean parse, so a file that fails halfway
contributes nothing.

**Why the size filter.** Two files can only share a hash if they share a length, so a size
that appears in no DAT entry is a guaranteed miss. Skipping those files avoids reading
multi-gigabyte disc images that could never match. The shortcut disables itself if the DAT
omits sizes, and never applies to archives, whose on-disk size is the compressed size.

**Why zips are opened.** DATs catalogue uncompressed ROMs, but ROMs are distributed
compressed. `HashEngine` hashes the payload inside a `.zip` rather than the container, and
the rename then applies the official title while keeping the `.zip` extension. When only
CRC32 is needed, the value stored in the zip's own header is used and the entry is never
decompressed.

**Why the walk does not use `DocumentFile.listFiles()`.** `DocumentFile` re-queries the
provider for each of `name`, `length`, and `isDirectory`, turning a few thousand ROMs into
tens of thousands of IPC round trips. `SafHandler` reads one `DocumentsContract` cursor per
directory instead. `DocumentFile` is still used for every mutation, where its API is the
right one.

**Why renames are validated as a batch.** Before anything is touched, the renamer checks
for two ROMs claiming the same official name and for target names already taken in that
folder, and orders the batch so a file gives up its name before another claims it. Blocked
entries are reported; the rest of the batch still runs.

## Building

Requires JDK 17+ and the Android SDK (compileSdk 35, minSdk 26).

```bash
./gradlew assembleDebug     # build
./gradlew testDebugUnitTest # unit tests
./gradlew installDebug      # install on a connected device
```

The JVM unit tests cover the DAT parser, the hash index, multi-DAT merging, the matcher's
collision handling, name sanitisation, and the file filter. They use kxml2 to supply the
same `XmlPullParser` implementation Android provides on device.

## Limitations

- Only `.zip` archives are inspected. `.7z` and `.rar` need a third-party library; those
  files are hashed as containers and will not match.
- Headered dumps (iNES, some SNES/Mega Drive dumps) hash differently from headerless DAT
  entries and will not match. Header-skipping is not implemented.
- The merged database is capped at `DatLoader.DEFAULT_MAX_ROM_ENTRIES`. A complete No-Intro
  collection exceeds it; the app loads what fits and tells you it stopped early.
- Renames cannot be undone from inside the app.
