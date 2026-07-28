# Soma Agent Notes

Read these first:

1. `docs/specs/prd.md` is the product contract. This is the canonical reference for the Soma specifications. Any inconsistencies found in other documents are superseded by this file.
2. `docs/specs/data-model.md` is the storage contract.
3. `docs/specs/config.yml` is the config contract.
4. `docs/tech/impl.md` is the implementation plan.
5. This is a greenfield project, don't carry any backward-compatibility overhead.

Common commands, run from this directory:

```shell
mvn spotless:apply    # format code first
mvn package
java -jar ./target/soma-0.1.0-SNAPSHOT.jar --help
java -jar ./target/soma-0.1.0-SNAPSHOT.jar status
```

## Rules

- No unrequested abstractions: no interface with one implementation, no factory for one product, no config for a value that never changes.
- No boilerplate, no scaffolding "for later", later can scaffold for itself.
- Deletion over addition. Boring over clever, clever is what someone decodes at 3am.
- Fewest files possible. Shortest working diff wins — but only once you understand the problem. The smallest change in the wrong place isn't lazy, it's a second bug.
