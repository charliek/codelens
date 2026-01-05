# Gradle Build Proxy Authentication Issue

## Executive Summary

Gradle builds fail in certain network environments due to Java's HttpURLConnection inability to authenticate with JWT-based HTTP proxies. This document analyzes the issue and proposes a practical solution using a local proxy adapter.

## The Problem

### Symptoms
```
java.io.IOException: Unable to tunnel through proxy.
Proxy returns "HTTP/1.1 401 Unauthorized"
```

### Root Cause Analysis

**Java HTTP Client Limitation:**
- Gradle uses Java's `HttpURLConnection` for all network operations
- `HttpURLConnection` has limited proxy authentication support
- Specifically struggles with:
  - Long JWT tokens (668+ characters in this environment)
  - Non-standard authentication schemes
  - Complex Authorization headers

**Working vs Non-Working Tools:**

| Tool | HTTP Client | JWT Proxy Auth | Status |
|------|-------------|----------------|--------|
| curl | libcurl | ✅ Works | ✅ Success |
| wget | libwget | ✅ Works | ✅ Success |
| Gradle | HttpURLConnection | ❌ Fails | ❌ 401 Error |
| Maven | HttpURLConnection | ❌ Fails | ❌ 401 Error |

**Evidence:**
```bash
# This works - curl uses libcurl which handles JWT auth
curl -x http://21.0.0.93:15004 https://plugins.gradle.org/
# Returns: 200 OK

# This fails - Gradle uses HttpURLConnection
./gradlew --version
# Returns: Unable to tunnel through proxy
```

### JDK Security Feature

Since JDK 8u111, Oracle disabled Basic authentication for HTTPS tunneling by default ([JDK-8171351](https://bugs.openjdk.org/browse/JDK-8171351)).

**Standard workaround:**
```properties
systemProp.jdk.http.auth.tunneling.disabledSchemes=""
```

**Result:** This fixes Basic auth issues but does NOT solve JWT token authentication problems.

## Attempted Solutions

1. ❌ Standard proxy properties in gradle.properties
2. ❌ Proxy credentials via systemProp.http.proxyUser/Password
3. ❌ JVM args via org.gradle.jvmargs
4. ❌ init.d proxy script
5. ❌ Re-enabling tunneling auth schemes
6. ❌ Gradle plugin repository configuration

**Conclusion:** All solutions fail because the fundamental issue is `HttpURLConnection`'s inability to handle this proxy's authentication mechanism, not configuration.

---

## Proposed Solution: Local Proxy Adapter

### Overview

Create a **Bun-based local proxy** that acts as a translation layer between Gradle and the environment's JWT proxy:

```
Gradle (no auth) → Bun Proxy (adds JWT) → Environment Proxy → Internet
         ↑                    ↑
   localhost:8899    handles JWT auth
```

### Architecture

**Flow:**
1. Gradle connects to `localhost:8899` (no authentication)
2. Bun proxy receives request, adds JWT token from environment
3. Bun proxy forwards to `21.0.0.93:15004` with proper auth
4. Response flows back through the chain

**Why This Works:**
- Bun/Node.js HTTP clients (like curl) CAN authenticate with JWT proxies
- Gradle only needs to talk to localhost (no proxy auth needed)
- Environment-specific complexity isolated in the adapter
- Can be enabled/disabled based on environment detection

### Implementation Specification

#### Project Structure
```
gradle-proxy-adapter/
├── src/
│   ├── proxy.ts              # Main Bun HTTP proxy server
│   ├── auth.ts               # JWT token extraction from environment
│   └── config.ts             # Configuration management
├── test/
│   ├── build.gradle.kts      # Test Gradle build to verify proxy
│   └── test-proxy.ts         # Proxy functionality tests
├── package.json
├── bunfig.toml
└── README.md
```

#### Core Features

**1. Proxy Server (src/proxy.ts)**
```typescript
// Pseudocode - do not implement yet
interface ProxyConfig {
  localPort: number;           // e.g., 8899
  upstreamHost: string;        // 21.0.0.93
  upstreamPort: number;        // 15004
  jwtToken: string;            // from env var
  verbose: boolean;
}

// HTTP proxy server that:
// - Listens on localhost:{localPort}
// - Handles CONNECT tunneling for HTTPS
// - Forwards HTTP requests with Proxy-Authorization header
// - Supports both HTTP and HTTPS upstream targets
// - Logs requests in verbose mode for debugging
```

**2. JWT Authentication (src/auth.ts)**
```typescript
// Pseudocode - do not implement yet
// Extract JWT from environment variables
// Support multiple env var names (HTTP_PROXY, HTTPS_PROXY, etc.)
// Parse proxy URL format: http://username:jwt@host:port
// Validate token exists and has minimum length
```

**3. Configuration (src/config.ts)**
```typescript
// Pseudocode - do not implement yet
// Environment detection:
// - Check for specific env markers to detect special environment
// - Load JWT token from environment
// - Set default ports and hosts
// - Support config file override for testing
```

#### Gradle Test Build (test/build.gradle.kts)

The project MUST include a working Gradle build to verify the proxy:

```kotlin
// Pseudocode - do not implement yet
plugins {
    kotlin("jvm") version "2.0.21"
}

repositories {
    mavenCentral()
    // Will fail without working proxy in this environment
}

dependencies {
    // Add several real dependencies to test downloading
    implementation("io.ktor:ktor-server-core:3.0.2")
    implementation("ch.qos.logback:logback-classic:1.5.6")
}

tasks.register("verifyProxy") {
    doLast {
        println("✅ Proxy working - dependencies downloaded successfully")
    }
}
```

**Verification Script:**
```bash
#!/usr/bin/env bash
# test/verify-proxy.sh

# Start proxy in background
bun run src/proxy.ts &
PROXY_PID=$!

# Wait for proxy to start
sleep 2

# Configure Gradle to use local proxy
export GRADLE_OPTS="-Dhttp.proxyHost=localhost -Dhttp.proxyPort=8899 -Dhttps.proxyHost=localhost -Dhttps.proxyPort=8899"

# Run Gradle build
cd test
./gradlew clean build

# Check result
if [ $? -eq 0 ]; then
    echo "✅ Proxy verification successful"
    kill $PROXY_PID
    exit 0
else
    echo "❌ Proxy verification failed"
    kill $PROXY_PID
    exit 1
fi
```

#### Environment Detection

**Setup Hook Integration:**
```typescript
// Pseudocode for environment detection
function isSpecialEnvironment(): boolean {
  // Check for environment-specific markers:
  // - Specific proxy host (21.0.0.93)
  // - JWT token in environment vars
  // - Specific env variables that only exist in this environment
  return process.env.HTTP_PROXY?.includes('21.0.0.93') ?? false;
}

function shouldStartProxy(): boolean {
  return isSpecialEnvironment() && !isProxyRunning();
}
```

**SessionStart Hook Usage:**
```typescript
// In .claude/hooks/SessionStart.ts (CodelLens project)
import { execSync } from 'child_process';

// Only run in special environment
if (process.env.HTTP_PROXY?.includes('21.0.0.93')) {
  // Check if proxy project is installed
  const proxyPath = '~/.local/gradle-proxy-adapter';

  if (existsSync(proxyPath)) {
    // Start proxy in background
    execSync(`cd ${proxyPath} && bun run src/proxy.ts &`);

    // Update gradle.properties to use localhost proxy
    const gradleProps = `
systemProp.http.proxyHost=localhost
systemProp.http.proxyPort=8899
systemProp.https.proxyHost=localhost
systemProp.https.proxyPort=8899
org.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=512m
`;
    writeFileSync('gradle.properties', gradleProps);
  }
}
```

### Installation & Usage

**One-Time Setup (in special environment):**
```bash
# Clone proxy adapter project
git clone https://github.com/yourusername/gradle-proxy-adapter.git ~/.local/gradle-proxy-adapter
cd ~/.local/gradle-proxy-adapter

# Install dependencies
bun install

# Verify it works with test build
bun run test/verify-proxy.sh
```

**Automatic Activation:**
- SessionStart hook detects environment
- Starts proxy if needed
- Updates gradle.properties automatically
- Gradle builds work transparently

**Manual Usage:**
```bash
# Start proxy manually
cd ~/.local/gradle-proxy-adapter
bun run src/proxy.ts

# In another terminal, build project
cd ~/your-kotlin-project
./gradlew build
# Uses localhost:8899 proxy automatically via gradle.properties
```

### Key Implementation Requirements

#### 1. CONNECT Tunneling Support
Most HTTPS traffic uses HTTP CONNECT tunneling. The proxy MUST:
```
Client: CONNECT plugins.gradle.org:443 HTTP/1.1

Proxy:
  1. Parse CONNECT request
  2. Open connection to upstream proxy with JWT auth
  3. Send CONNECT to upstream:
     CONNECT plugins.gradle.org:443 HTTP/1.1
     Proxy-Authorization: Bearer <jwt-token>
  4. Receive 200 Connection Established from upstream
  5. Send 200 back to client
  6. Tunnel bytes bidirectionally
```

#### 2. HTTP Request Forwarding
For non-HTTPS requests:
```
Client: GET http://repo.maven.org/... HTTP/1.1

Proxy:
  1. Parse HTTP request
  2. Add Proxy-Authorization header with JWT
  3. Forward to upstream proxy
  4. Return response to client
```

#### 3. Error Handling
- Log all proxy authentication failures
- Detect missing JWT token and provide helpful error
- Handle upstream proxy timeout/connection errors
- Graceful shutdown on SIGTERM/SIGINT

#### 4. Performance Considerations
- Use streaming for large responses (JARs can be 100MB+)
- Maintain keep-alive connections to upstream
- Support concurrent connections (Gradle makes many parallel requests)
- Minimal latency overhead (<10ms per request)

### Testing Strategy

**Unit Tests:**
```bash
bun test
# Tests for:
# - JWT extraction from various env var formats
# - Config loading and validation
# - Request parsing and header injection
```

**Integration Test:**
```bash
# The Gradle build in test/ is the integration test
bun run test/verify-proxy.sh

# Should successfully:
# 1. Start proxy
# 2. Download Gradle wrapper
# 3. Resolve Gradle plugins
# 4. Download Maven dependencies
# 5. Complete build
```

**Manual Verification:**
```bash
# Start proxy with verbose logging
VERBOSE=true bun run src/proxy.ts

# In another terminal, test with curl
export http_proxy=http://localhost:8899
export https_proxy=http://localhost:8899
curl -v https://plugins.gradle.org/

# Should see in proxy logs:
# → Incoming CONNECT request to plugins.gradle.org:443
# → Forwarding with JWT auth to 21.0.0.93:15004
# ← 200 Connection Established from upstream
# → 200 Connection Established to client
# ← Tunneling 1234 bytes...
```

### Security Considerations

**1. Localhost Only:**
```typescript
// Only bind to localhost - never expose on network
server.listen(8899, '127.0.0.1');
```

**2. No JWT Logging:**
```typescript
// NEVER log the JWT token in verbose mode
console.log(`Proxy-Authorization: Bearer ${token.substring(0, 20)}...`);
```

**3. Environment Isolation:**
```typescript
// Only activate in detected environment
if (!isSpecialEnvironment()) {
  console.log('Not in special environment, exiting');
  process.exit(0);
}
```

**4. Secure Defaults:**
- No config file in repo with actual credentials
- JWT loaded from environment only
- Fail closed if JWT not found

### Project Metadata

**package.json:**
```json
{
  "name": "gradle-proxy-adapter",
  "version": "1.0.0",
  "description": "Local HTTP proxy adapter for Gradle in JWT-authenticated environments",
  "main": "src/proxy.ts",
  "scripts": {
    "start": "bun run src/proxy.ts",
    "test": "bun test",
    "verify": "bash test/verify-proxy.sh"
  },
  "keywords": ["gradle", "proxy", "jwt", "authentication"],
  "license": "MIT"
}
```

**README.md should include:**
- Why this exists (HttpURLConnection limitation)
- How it works (architecture diagram)
- Installation instructions
- Environment detection logic
- Testing with included Gradle build
- Troubleshooting guide

### Success Criteria

✅ **The solution is complete when:**

1. Bun proxy starts successfully in special environment
2. `test/build.gradle.kts` builds successfully via proxy
3. Gradle can download:
   - Gradle wrapper distribution
   - Gradle plugins from plugins.gradle.org
   - Maven dependencies from mavenCentral()
4. Proxy handles concurrent Gradle requests
5. SessionStart hook auto-configures CodelLens project
6. No configuration needed for other environments
7. Comprehensive README for troubleshooting

### Benefits of This Approach

✅ **Advantages:**
- Solves the fundamental HttpURLConnection limitation
- Environment-specific complexity isolated to separate project
- Can be tested independently with included Gradle build
- Automatic activation via SessionStart hook
- No changes to main CodelLens codebase
- Works for ANY Gradle/Maven project in this environment
- Reusable across projects

✅ **Clean Separation:**
- CodelLens project: Clean, no proxy config
- gradle-proxy-adapter: Environment-specific logic
- SessionStart hook: Glue code that auto-configures

✅ **Testability:**
- Includes Gradle build that ONLY works in this environment
- Verifies the actual problem is solved
- Users outside environment can't accidentally run it

---

## Alternative Approaches Considered

### 1. Pre-populated Gradle Cache
**Approach:** Download all dependencies externally, copy to `~/.gradle/caches`
**Rejected:** Fragile, version-specific, hard to maintain

### 2. Gradle Wrapper Pre-installed
**Approach:** Include full Gradle distribution in repo
**Rejected:** Bloats repo, doesn't solve plugin/dependency downloads

### 3. Use Gradle Daemon Mode
**Approach:** Keep Gradle daemon running outside environment
**Rejected:** Doesn't solve initial download problem

### 4. Build Server / CI
**Approach:** Build JARs externally, commit to repo
**Rejected:** Defeats purpose of local development

**Conclusion:** Local proxy adapter is the most robust solution that actually fixes the root cause.

---

## References

- [JDK-8171351: Basic authentication disabled for HTTPS tunneling](https://bugs.openjdk.org/browse/JDK-8171351)
- [Atlassian: Basic authentication fails for outgoing proxy in Java 8u111](https://confluence.atlassian.com/kb/basic-authentication-fails-for-outgoing-proxy-in-java-8u111-909643110.html)
- [Gradle Forums: Unable to tunnel through proxy](https://discuss.gradle.org/t/unable-to-tunnel-through-proxy/49061)
- [HttpURLConnection Proxy Documentation](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/HttpURLConnection.html)

---

## Next Steps

1. Create `gradle-proxy-adapter` project on GitHub
2. Implement Bun proxy with CONNECT tunneling support
3. Add Gradle test build that verifies proxy works
4. Test in special environment with verification script
5. Add SessionStart hook to CodelLens to auto-configure
6. Document in both project READMEs
7. Gradle builds work everywhere! 🎉
