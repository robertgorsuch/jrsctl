# ADR-0043: jrsctl manages JasperReports Server on Apache Tomcat only, and not inside a container

Status: accepted · Date: 2026-09-26 · Spec: §19 Q2, §12.0, §12.1, §18 · Issue #118

## Context

Spec §19 Q2 left open whether deployments other than Tomcat must be supported. The vendor certifies more: the platform support sheet 10.1 (pp.5, 18) lists JBoss EAP 8 and WildFly 36 beside Tomcat, and Docker/Kubernetes on ECS, EKS, AKS and OpenShift (vendor doc review P6, `docs/reviews/2026-09-17-vendor-doc-review.md`).

Everything that mutates an installation assumes Tomcat: the layout detection (`webapps/`, `server.xml` ports), the service kinds and the process scan, `--tomcat-dir` and the certified-Tomcat ranges in the matrix, and clearing Tomcat's `work`/`temp` after an upgrade. Before this decision a JBoss or WildFly home simply looked like "not a JasperReports Server tree" (`init`, exit 2), which is safe but tells the operator nothing. Nothing detected a container at all.

Every installation met so far is Tomcat: the binary installer, the WAR + buildomatic production install (#147), and the three field tests (#36). Nobody has asked for JBoss, WildFly or container support, and none of them can be tested on the build machine.

## Options

1. **Tomcat only, stated and detected.** Refuse a JBoss/WildFly home and a container with a clear reason; point to what still works.
2. **Add JBoss EAP / WildFly.** A different webapp location (`standalone/deployments/*.war`, often a packed WAR, which the file-level hotfix swap cannot patch in place), different service control (JBoss CLI or `standalone.sh`), different caches to clear after an upgrade, new matrix ranges. Large, and untestable here.
3. **Container-native support.** A hotfix or upgrade made inside a running container is lost when the image is rebuilt; the right place for both is the image build. That is a different product model.

## Decision

Option 1. The operator asked for containers to be refused, not warned about.

- `jrsctl init` refuses, before detecting anything and with **exit 6** (unsupported): when jrsctl runs inside a container, or when `--install-dir` names a JBoss EAP or WildFly home (a `jboss-modules.jar` or `standalone/deployments` at the directory or up to three levels above it) that has no Tomcat layout. The message says why and names the remediation. `init --remote` is never refused: REST export and import work against any reachable server, container or not.
- `jrsctl doctor` gains a `deployment` item, just before `layout`, that fails on the same conditions. On a JBoss/WildFly home the `layout` item is skipped instead of failing a second time. Like `compat`, a `deployment` failure alone exits **6**. A remote-only configuration skips it with the other local items.
- A container is recognised on Linux by the markers Docker (`/.dockerenv`), Podman (`/run/.containerenv`) and Kubernetes (`KUBERNETES_SERVICE_HOST`, `/var/run/secrets/kubernetes.io/serviceaccount`) leave. Windows containers are not detected. `-Djrsctl.containerCheck=false` turns the check off for jrsctl's own test suites (surefire and the acceptance launcher set it), so the build passes when it runs in a container. It is not an operator setting and is not documented as one.

## Consequences

- An operator on an unsupported deployment learns it at `init` or `doctor`, with a reason, instead of from an unexplained layout failure or partway through an upgrade.
- Supporting JBoss/WildFly later means: a service kind for JBoss (CLI or scripts), the webapp at `standalone/deployments` with a strategy for packed WARs, the post-upgrade cache steps for that server, and container ranges in the compatibility matrix. That would be a new ADR superseding this one.
- A jrsctl inside a container that would otherwise have worked (next to the files, with the service stopped by hand) is refused too. That is deliberate: its changes would not survive the next image build.
