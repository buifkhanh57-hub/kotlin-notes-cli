# kotlin-notes-cli

A console notes manager written in idiomatic Kotlin:
`data class`, companion objects, `split`/`let` chains, `when` dispatch,
and simple CSV persistence in `~/.kotlin-notes.csv`.

## Build & run
```bash
kotlinc src/Main.kt -include-runtime -d notes.jar
java -jar notes.jar          # interactive shell
java -jar notes.jar demo     # add sample notes and list them
```

## Commands
`add <title> | <body>` · `list` · `find <keyword>` · `del <id>` · `help` · `exit`
