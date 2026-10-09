# Vault recovery implementation plan

Use executing-plans with the approved bundle and vault-recovery spec. No additional design approval required under the user's standing project authorization.

- [x] Add RED regressions for verified manifest/chunk recovery, replica fallback, cancellation and range-only hydration.
- [x] Implement bounded atomic downloads and validated manifest restoration without remote mutation.
- [x] Add complete media restoration with full checksum and narrow source/manifest-bound project publication.
- [x] Wire owner/MCP durable jobs, current scopes, STOP and protocol compatibility.
- [ ] Run fresh Android/Node/Worker tests and bounded review; publish only after prior storage CI is resolved.
- [ ] Verify matching Gradle/APK/device recovery evidence, append continuity, then continue the complete roadmap.

Current source: schema7/app3.4.9; fresh 76 main/36 test Java classes,242 JUnit cases GREEN. Node282/20/22 and actual Wrangler bundle pass. Pure core40 timeline/52 Vault/14 DSP/510 narration checks pass. Initial hydrationRED6, restorationRED4 and ownerRED2/relayRED1 observed. Review-triggering variants reproduced seven failures in45 focused cases; corrected source GREEN45. A final manifest-cancel regression observed RED1 then final full GREEN242. Independent follow-up closes four Important findings plus cancellation; source binding, replica fallback, staged binary integrity and deterministic verified-prefix restart are covered. Matching recovery APK/device CI pending; preceding storage806620a allgreen222unit/7device.
