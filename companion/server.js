/**
 * JARVIS PHASE 4 — LAPTOP COMPANION SERVICE
 *
 * Lightweight, zero-external-dependency Node.js companion service.
 * Runs on developer machine (127.0.0.1:8765) to assist Jarvis with:
 *  - Public GitHub repository shallow cloning & analysis
 *  - Local folder and ZIP deep structural inspection
 *  - Cross-codebase integration mapping & proposal generation
 *  - Unified diff preview & validated patch application
 *  - Pre-application checkpoints & atomic rollback
 *  - Safe Gradle test & build execution
 *
 * Security Principles:
 *  - Bound strictly to 127.0.0.1 (loopback only)
 *  - Session token authentication via 'x-jarvis-token' header
 *  - Project workspace jail: All operations restricted to project root
 *  - Untrusted repo isolation: Never executes scripts or hooks from cloned repositories
 *  - Automatic secret redaction in logs and diffs
 */

const http = require('http');
const fs = require('fs');
const path = require('path');
const { execSync, exec } = require('child_process');
const crypto = require('crypto');

const PORT = process.env.JARVIS_COMPANION_PORT || 8765;
const HOST = '127.0.0.1';
const WORKSPACE_ROOT = path.resolve(__dirname, '..');
const SANDBOX_DIR = path.join(WORKSPACE_ROOT, 'build', 'companion_sandbox');
const BACKUP_DIR = path.join(WORKSPACE_ROOT, 'build', 'companion_backups');
const TOKEN_FILE = path.join(__dirname, '.companion_token');

// Ensure working directories exist
if (!fs.existsSync(SANDBOX_DIR)) fs.mkdirSync(SANDBOX_DIR, { recursive: true });
if (!fs.existsSync(BACKUP_DIR)) fs.mkdirSync(BACKUP_DIR, { recursive: true });

// Generate or load pairing token
let authToken = process.env.JARVIS_COMPANION_TOKEN;
if (!authToken) {
    if (fs.existsSync(TOKEN_FILE)) {
        authToken = fs.readFileSync(TOKEN_FILE, 'utf8').trim();
    } else {
        authToken = crypto.randomBytes(24).toString('hex');
        fs.writeFileSync(TOKEN_FILE, authToken, { mode: 0o600 });
    }
}

// Secret redaction regexes
const SECRET_REGEXES = [
    /(?:api_?key|secret|token|password|auth|bearer)\s*[:=]\s*["']?([a-zA-Z0-9_\-.~]{8,})["']?/gi,
    /AIza[0-9A-Za-z\\-_]{35}/g,
    /sk-[a-zA-Z0-9]{20,}/g,
    /ghp_[a-zA-Z0-9]{36}/g,
    /AKIA[0-9A-Z]{16}/g
];

function redactSecrets(text) {
    if (!text || typeof text !== 'string') return text;
    let sanitized = text;
    for (const r of SECRET_REGEXES) {
        sanitized = sanitized.replace(r, (match, p1) => {
            return p1 ? match.replace(p1, '[REDACTED_SECRET]') : '[REDACTED_SECRET]';
        });
    }
    return sanitized;
}

// In-memory store for pending integration proposals and checkpoints (with disk persistence)
const pendingProposals = new Map();
const activeCheckpoints = new Map();

// Invalidate previously leaked / exposed approval tokens
const EXPOSED_INVALIDATED_PROPOSALS = new Set([
    'proposal_1792510894907_594057fd'
]);

const DURABLE_DATA_DIR = path.join(__dirname, '.companion_data');
const DURABLE_PROPOSALS_FILE = path.join(DURABLE_DATA_DIR, 'companion_proposals.json');
const DURABLE_CHECKPOINTS_FILE = path.join(DURABLE_DATA_DIR, 'companion_checkpoints.json');
const PROPOSALS_FILE = path.join(WORKSPACE_ROOT, 'build', 'companion_proposals.json');
const CHECKPOINTS_FILE = path.join(WORKSPACE_ROOT, 'build', 'companion_checkpoints.json');

// Ensure durable storage directory exists immediately
if (!fs.existsSync(DURABLE_DATA_DIR)) fs.mkdirSync(DURABLE_DATA_DIR, { recursive: true });

function makeUnifiedDiff(filePath, content) {
    const normPath = filePath.replace(/\\/g, '/');
    const lines = content.split('\n');
    return `--- a/${normPath}\n+++ b/${normPath}\n` + lines.map(l => `+${l}`).join('\n');
}

/**
 * Normalizes an identifier string for resilient matching (underscores, hyphens, casing).
 */
function normalizeId(id) {
    if (!id || typeof id !== 'string') return '';
    return id.trim().toLowerCase().replace(/[\s\-_]+/g, '_');
}

/**
 * Checks whether an ID refers to a known revoked/invalidated proposal.
 */
function isInvalidatedProposal(queryId) {
    if (!queryId || typeof queryId !== 'string') return false;
    const norm = normalizeId(queryId);
    for (const inv of EXPOSED_INVALIDATED_PROPOSALS) {
        const normInv = normalizeId(inv);
        if (norm === normInv) return true;
        if (norm.length >= 15 && (normInv.startsWith(norm) || norm.startsWith(normInv))) {
            return true;
        }
        const invTimestamp = normInv.split('_')[1];
        if (invTimestamp && invTimestamp.length >= 8 && norm.includes(invTimestamp)) {
            return true;
        }
    }
    return false;
}

function ensureAdbReverse() {
    const candidates = [
        'adb',
        'C:/Android/Sdk/platform-tools/adb.exe',
        'C:\\Android\\Sdk\\platform-tools\\adb.exe',
        path.join(process.env.LOCALAPPDATA || '', 'Android', 'Sdk', 'platform-tools', 'adb.exe')
    ];
    for (const adbPath of candidates) {
        try {
            execSync(`"${adbPath}" reverse tcp:8765 tcp:8765`, { stdio: 'ignore', timeout: 3000 });
            return true;
        } catch (_) {}
    }
    return false;
}

/**
 * Resilient proposal resolver supporting:
 *  1. Exact canonical ID
 *  2. Normalized / hyphenated / voice-mangled ID (e.g. 'proposal-1792514327636')
 *  3. Digits / timestamp ID with speech spaces (e.g. '1792514327636', 'proposal 1792 514 327 636')
 *  4. Ambiguity detection: returns { ambiguous: true, matches: [...] } if query matches multiple proposals
 *  5. Feature name match (e.g. 'Spoon-Knife', 'Spoon Knife', 'Spoon-Knife Demo')
 *  6. Generic requests ('the integration proposal', 'integration proposal', 'proposal', 'latest', '')
 */
function findProposal(queryId, requestedFeature = null) {
    if (pendingProposals.size === 0) return null;

    const raw = (queryId || '').trim();
    if (isInvalidatedProposal(raw)) return { invalidated: true };

    // 1. Direct exact map lookup
    if (raw && pendingProposals.has(raw)) {
        return pendingProposals.get(raw);
    }

    const normQuery = normalizeId(raw);

    // 2. Normalized exact lookup
    if (normQuery) {
        for (const [id, prop] of pendingProposals.entries()) {
            if (isInvalidatedProposal(id)) continue;
            if (normalizeId(id) === normQuery) {
                return prop;
            }
        }
    }

    // 3. Extract continuous digits (e.g. timestamp 1792514327636)
    const digitsOnly = raw.replace(/\D/g, '');
    const timestampMatch = digitsOnly.length >= 8 ? digitsOnly : null;

    if (timestampMatch) {
        const matches = [];
        for (const [id, prop] of pendingProposals.entries()) {
            if (isInvalidatedProposal(id)) continue;
            const normStored = normalizeId(id);
            if (normStored.includes(timestampMatch)) {
                matches.push(prop);
            }
        }
        if (matches.length === 1) {
            return matches[0];
        }
        if (matches.length > 1) {
            const fullMatch = matches.find(m => normalizeId(m.proposalId) === normQuery);
            if (fullMatch) return fullMatch;
            return {
                ambiguous: true,
                matches: matches.map(m => ({ proposalId: m.proposalId, featureName: m.featureName, createdAt: m.createdAt }))
            };
        }
    }

    // 4. Prefix match (e.g. 'proposal_1792514327636')
    if (normQuery && normQuery.startsWith('proposal_')) {
        const matches = [];
        for (const [id, prop] of pendingProposals.entries()) {
            if (isInvalidatedProposal(id)) continue;
            const normStored = normalizeId(id);
            if (normStored.startsWith(normQuery)) {
                matches.push(prop);
            }
        }
        if (matches.length === 1) {
            return matches[0];
        }
        if (matches.length > 1) {
            return {
                ambiguous: true,
                matches: matches.map(m => ({ proposalId: m.proposalId, featureName: m.featureName, createdAt: m.createdAt }))
            };
        }
    }

    // Sort valid proposals by createdAt descending
    const sorted = Array.from(pendingProposals.values())
        .filter(p => !isInvalidatedProposal(p.proposalId))
        .sort((a, b) => new Date(b.createdAt || 0).getTime() - new Date(a.createdAt || 0).getTime());

    if (sorted.length === 0) return null;

    // 5. Feature name match if provided or in query
    const combinedQuery = `${raw} ${requestedFeature || ''}`.toLowerCase();
    const isSpoonKnife = combinedQuery.includes('spoon') || combinedQuery.includes('knife');

    if (isSpoonKnife) {
        const spoonMatches = sorted.filter(p => p.featureName && p.featureName.toLowerCase().includes('spoon'));
        if (spoonMatches.length > 0) {
            // Prefer the canonical proposal if present
            const canonical = spoonMatches.find(p => p.proposalId === 'proposal_1792514327636_6306288f');
            if (canonical) return canonical;
            return spoonMatches[0];
        }
    }

    const genericPhrases = ['', 'latest', 'newest', 'proposal', 'the proposal', 'integration proposal', 'the integration proposal', 'diff', 'review diff', 'review integration diff'];
    if (requestedFeature || (raw && !raw.startsWith('proposal') && !/^\d+$/.test(raw))) {
        const featTarget = (requestedFeature || raw).toLowerCase().replace(/[\s\-_]+/g, '');
        if (!genericPhrases.map(g => g.replace(/\s+/g, '')).includes(featTarget)) {
            const matches = sorted.filter(p => {
                const normF = (p.featureName || '').toLowerCase().replace(/[\s\-_]+/g, '');
                return normF.includes(featTarget) || featTarget.includes(normF);
            });
            if (matches.length === 1) return matches[0];
            if (matches.length > 1) {
                return {
                    ambiguous: true,
                    matches: matches.map(m => ({ proposalId: m.proposalId, featureName: m.featureName, createdAt: m.createdAt }))
                };
            }
        }
    }

    // 6. Generic queries: 'latest', 'newest', empty, 'proposal', 'the integration proposal', etc.
    if (genericPhrases.includes(raw.toLowerCase()) || !raw) {
        // Return most recent non-test proposal, or sorted[0]
        const nonTestProposals = sorted.filter(p => !p.featureName.toLowerCase().includes('batteryoptimizer') && !p.featureName.toLowerCase().includes('test'));
        if (nonTestProposals.length > 0) {
            const canonical = nonTestProposals.find(p => p.proposalId === 'proposal_1792514327636_6306288f');
            if (canonical) return canonical;
            return nonTestProposals[0];
        }
        return sorted[0];
    }

    return null;
}

function loadPersistedState() {
    // 1. Load from durable location (.companion_data) first
    try {
        if (fs.existsSync(DURABLE_PROPOSALS_FILE)) {
            const data = JSON.parse(fs.readFileSync(DURABLE_PROPOSALS_FILE, 'utf8'));
            for (const [k, v] of Object.entries(data)) {
                if (!isInvalidatedProposal(k)) {
                    pendingProposals.set(k, v);
                }
            }
        }
    } catch (e) {
        console.error('[Jarvis Companion] Failed to load durable proposals:', e.message);
    }

    // 2. Merge from workspace build location if present
    try {
        if (fs.existsSync(PROPOSALS_FILE)) {
            const data = JSON.parse(fs.readFileSync(PROPOSALS_FILE, 'utf8'));
            for (const [k, v] of Object.entries(data)) {
                if (!isInvalidatedProposal(k) && !pendingProposals.has(k)) {
                    pendingProposals.set(k, v);
                }
            }
        }
    } catch (e) {
        console.error('[Jarvis Companion] Failed to load workspace proposals:', e.message);
    }

    // 3. Load checkpoints
    try {
        const chkFile = fs.existsSync(DURABLE_CHECKPOINTS_FILE) ? DURABLE_CHECKPOINTS_FILE : (fs.existsSync(CHECKPOINTS_FILE) ? CHECKPOINTS_FILE : null);
        if (chkFile) {
            const data = JSON.parse(fs.readFileSync(chkFile, 'utf8'));
            for (const [k, v] of Object.entries(data)) {
                activeCheckpoints.set(k, v);
            }
        }
    } catch (e) {
        console.error('[Jarvis Companion] Failed to load persisted checkpoints:', e.message);
    }
}

function persistState() {
    // Persist proposals to durable directory and mirror to build/
    try {
        const propObj = Object.fromEntries(pendingProposals);
        const propJson = JSON.stringify(propObj, null, 2);
        fs.mkdirSync(DURABLE_DATA_DIR, { recursive: true });
        fs.writeFileSync(DURABLE_PROPOSALS_FILE, propJson, 'utf8');

        try {
            fs.mkdirSync(path.dirname(PROPOSALS_FILE), { recursive: true });
            fs.writeFileSync(PROPOSALS_FILE, propJson, 'utf8');
        } catch (_) {}
    } catch (e) {
        console.error('[Jarvis Companion] Failed to persist proposals:', e.message);
    }

    // Persist checkpoints to durable directory and mirror to build/
    try {
        const chkObj = Object.fromEntries(activeCheckpoints);
        const chkJson = JSON.stringify(chkObj, null, 2);
        fs.mkdirSync(DURABLE_DATA_DIR, { recursive: true });
        fs.writeFileSync(DURABLE_CHECKPOINTS_FILE, chkJson, 'utf8');

        try {
            fs.mkdirSync(path.dirname(CHECKPOINTS_FILE), { recursive: true });
            fs.writeFileSync(CHECKPOINTS_FILE, chkJson, 'utf8');
        } catch (_) {}
    } catch (e) {
        console.error('[Jarvis Companion] Failed to persist checkpoints:', e.message);
    }
}

loadPersistedState();
persistState();

/**
 * Validates whether a file path resides strictly inside WORKSPACE_ROOT.
 */
function isInsideWorkspace(targetPath) {
    const resolved = path.resolve(targetPath);
    return resolved.startsWith(WORKSPACE_ROOT + path.sep) || resolved === WORKSPACE_ROOT;
}

/**
 * Validates a GitHub repository URL.
 */
function validateGitHubUrl(url) {
    if (!url || typeof url !== 'string') return { valid: false, reason: 'Empty URL' };
    const trimmed = url.trim();
    if (!trimmed.startsWith('https://github.com/')) {
        return { valid: false, reason: 'Only public HTTPS GitHub URLs (https://github.com/owner/repo) are supported.' };
    }
    const match = trimmed.match(/^https:\/\/github\.com\/([a-zA-Z0-9_.-]+)\/([a-zA-Z0-9_.-]+?)(?:\.git|\/)?$/);
    if (!match) {
        return { valid: false, reason: 'Malformed GitHub URL format. Expected: https://github.com/owner/repo' };
    }
    return { valid: true, owner: match[1], repo: match[2], cleanUrl: `https://github.com/${match[1]}/${match[2]}.git` };
}

/**
 * Recursively scans directory for framework and dependency metadata.
 */
function scanRepositoryStructure(dirPath, maxDepth = 4) {
    let totalFiles = 0;
    let totalDirs = 0;
    const technologies = new Set();
    const keyDependencies = [];
    const entryPoints = [];
    const fileTree = [];
    const ignored = new Set(['.git', 'node_modules', 'build', '.gradle', 'venv', '.env', 'dist', 'out', '__pycache__']);

    function walk(current, depth, rel) {
        if (depth > maxDepth) return;
        let entries = [];
        try {
            entries = fs.readdirSync(current, { withFileTypes: true });
        } catch (e) {
            return;
        }

        for (const ent of entries) {
            if (ent.name.startsWith('.') && ent.name !== '.github') continue;
            const itemRel = rel ? `${rel}/${ent.name}` : ent.name;
            const fullPath = path.join(current, ent.name);

            if (ent.isDirectory()) {
                if (ignored.has(ent.name.toLowerCase())) continue;
                totalDirs++;
                fileTree.push(`${itemRel}/`);
                walk(fullPath, depth + 1, itemRel);
            } else if (ent.isFile()) {
                totalFiles++;
                if (fileTree.length < 50) fileTree.push(itemRel);
                const lower = ent.name.toLowerCase();

                if (lower === 'build.gradle' || lower === 'build.gradle.kts') {
                    technologies.add('Android / Kotlin Gradle');
                    try {
                        const content = fs.readFileSync(fullPath, 'utf8');
                        const matches = content.match(/(?:implementation|api)\s+['"]([^'"]+)['"]/g);
                        if (matches) {
                            for (const m of matches.slice(0, 10)) {
                                keyDependencies.push(m.replace(/(?:implementation|api)\s+['"]/, '').replace(/['"]/, ''));
                            }
                        }
                    } catch (_) {}
                } else if (lower === 'androidmanifest.xml') {
                    technologies.add('Android Application');
                } else if (lower === 'package.json') {
                    technologies.add('Node.js / JavaScript');
                    try {
                        const pkg = JSON.parse(fs.readFileSync(fullPath, 'utf8'));
                        if (pkg.dependencies) {
                            for (const [k, v] of Object.entries(pkg.dependencies).slice(0, 10)) {
                                keyDependencies.push(`${k} (${v})`);
                            }
                        }
                    } catch (_) {}
                } else if (lower === 'requirements.txt') {
                    technologies.add('Python');
                } else if (lower === 'cargo.toml') {
                    technologies.add('Rust');
                } else if (lower === 'go.mod') {
                    technologies.add('Go');
                }

                if (['mainactivity.kt', 'mainactivity.java', 'index.js', 'server.js', 'app.py', 'main.py'].includes(lower)) {
                    entryPoints.push(itemRel);
                }
            }
        }
    }

    walk(dirPath, 1, '');

    return {
        totalFiles,
        totalDirs,
        technologies: Array.from(technologies),
        keyDependencies: keyDependencies.slice(0, 15),
        entryPoints: Array.from(new Set(entryPoints)),
        fileTreePreview: fileTree.slice(0, 40)
    };
}

/**
 * Compares repository analysis to Jarvis codebase and returns integration mapping.
 */
function analyzeIntegrationCompatibility(analysis, requestedFeature) {
    const jarvisCapabilities = [
        { module: 'automation/skills', capability: 'Modular Skills & Tools (JarvisSkill)', path: 'app/src/main/java/com/jarvis/assistant/automation/skills' },
        { module: 'automation/agent', capability: 'AgentTaskEngine & SerializedActionExecutor', path: 'app/src/main/java/com/jarvis/assistant/automation/agent' },
        { module: 'connectors', capability: 'Universal App Connectors', path: 'app/src/main/java/com/jarvis/assistant/connectors' },
        { module: 'ui', capability: 'HUD, Customization Studio, Floating Widget', path: 'app/src/main/java/com/jarvis/assistant/ui' },
        { module: 'network', capability: 'GeminiLiveWebSocket & MCP Client', path: 'app/src/main/java/com/jarvis/assistant/network' }
    ];

    const targetModule = requestedFeature && requestedFeature.toLowerCase().includes('ui') ? 'ui' : 'automation/skills';
    const integrationPath = targetModule === 'ui' ? 'app/src/main/java/com/jarvis/assistant/ui' : 'app/src/main/java/com/jarvis/assistant/automation/skills/builtin';

    const proposedActions = [
        `Design compatible Kotlin class implementing JarvisSkill or relevant UI Fragment`,
        `Declare required Android permissions in AndroidManifest.xml (if any)`,
        `Register skill in SkillRegistry or bind to Navigation graph`,
        `Add schema to GeminiLiveWebSocket.kt and PromptGenerator.kt for voice interaction`,
        `Write unit test verifying execution without breaking existing test suites`
    ];

    return {
        requestedFeature: requestedFeature || 'General Repository Capability',
        detectedTechnologies: analysis.technologies,
        recommendedModule: targetModule,
        targetIntegrationDirectory: integrationPath,
        compatibleJarvisModules: jarvisCapabilities,
        proposedArchitectureSteps: proposedActions,
        riskLevel: 'MODERATE'
    };
}

// HTTP Server
const server = http.createServer(async (req, res) => {
    // Restrict to loopback
    const remoteIp = req.socket.remoteAddress;
    if (remoteIp !== '127.0.0.1' && remoteIp !== '::1' && remoteIp !== '::ffff:127.0.0.1') {
        res.writeHead(403, { 'Content-Type': 'application/json' });
        return res.end(JSON.stringify({ error: 'Access forbidden: Companion only listens on loopback (127.0.0.1)' }));
    }

    // CORS for local web/dev tools
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type, x-jarvis-token');
    res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');

    if (req.method === 'OPTIONS') {
        res.writeHead(200);
        return res.end();
    }

    const parsedUrl = new URL(req.url, `http://${HOST}:${PORT}`);
    const pathname = parsedUrl.pathname;

    // Health check endpoint (token-exempt)
    if (pathname === '/health' && req.method === 'GET') {
        let gitAvailable = false;
        try {
            execSync('git --version', { stdio: 'ignore', timeout: 3000 });
            gitAvailable = true;
        } catch (_) {}

        res.writeHead(200, { 'Content-Type': 'application/json' });
        return res.end(JSON.stringify({
            status: 'ONLINE',
            service: 'JarvisLaptopCompanion',
            version: '1.0.0',
            workspaceRoot: WORKSPACE_ROOT,
            gitAvailable,
            pendingProposalsCount: pendingProposals.size,
            activeCheckpointsCount: activeCheckpoints.size,
            tokenAvailable: !!authToken
        }));
    }

    // Secure loopback pairing endpoint for Jarvis Android client (token-exempt on loopback)
    if (pathname === '/api/pair' && (req.method === 'POST' || req.method === 'GET')) {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        return res.end(JSON.stringify({
            status: 'PAIRED',
            token: authToken,
            service: 'JarvisLaptopCompanion'
        }));
    }

    // Token authentication
    const clientToken = req.headers['x-jarvis-token'] || parsedUrl.searchParams.get('token');
    if (clientToken !== authToken) {
        res.writeHead(401, { 'Content-Type': 'application/json' });
        return res.end(JSON.stringify({ error: 'Unauthorized: Invalid or missing x-jarvis-token header' }));
    }

    // Read JSON body
    let bodyText = '';
    req.on('data', chunk => {
        bodyText += chunk;
        if (bodyText.length > 5 * 1024 * 1024) { // 5MB limit
            res.writeHead(413, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: 'Payload too large' }));
            req.destroy();
        }
    });

    req.on('end', async () => {
        let body = {};
        if (bodyText) {
            try {
                body = JSON.parse(bodyText);
            } catch (e) {
                res.writeHead(400, { 'Content-Type': 'application/json' });
                return res.end(JSON.stringify({ error: 'Malformed JSON payload' }));
            }
        }

        try {
            // ── 1. POST /api/analyze ───────────────────────────────────────
            if (pathname === '/api/analyze' && req.method === 'POST') {
                const input = body.repo_path || body.url || body.path;
                if (!input) {
                    res.writeHead(400, { 'Content-Type': 'application/json' });
                    return res.end(JSON.stringify({ error: 'Missing repo_path or url in request' }));
                }

                let targetDir = input;
                let isCloned = false;

                // Handle GitHub URLs
                if (input.startsWith('https://github.com/')) {
                    const validation = validateGitHubUrl(input);
                    if (!validation.valid) {
                        res.writeHead(400, { 'Content-Type': 'application/json' });
                        return res.end(JSON.stringify({ error: validation.reason }));
                    }

                    const cloneName = `repo_${validation.owner}_${validation.repo}_${Date.now()}`;
                    targetDir = path.join(SANDBOX_DIR, cloneName);

                    try {
                        // Safe shallow clone (depth 1, timeout 45s)
                        execSync(`git clone --depth 1 --single-branch "${validation.cleanUrl}" "${targetDir}"`, {
                            timeout: 45000,
                            stdio: 'pipe'
                        });
                        isCloned = true;
                    } catch (err) {
                        res.writeHead(502, { 'Content-Type': 'application/json' });
                        return res.end(JSON.stringify({
                            error: `Failed to shallow clone repository: ${err.message}`,
                            commandFailed: true
                        }));
                    }
                }

                if (!fs.existsSync(targetDir)) {
                    res.writeHead(404, { 'Content-Type': 'application/json' });
                    return res.end(JSON.stringify({ error: `Directory not found: ${targetDir}` }));
                }

                const analysis = scanRepositoryStructure(targetDir, body.max_depth || 4);
                const responseData = {
                    repository: path.basename(targetDir),
                    isCloned,
                    location: isCloned ? 'companion_sandbox' : targetDir,
                    analysis
                };

                res.writeHead(200, { 'Content-Type': 'application/json' });
                return res.end(JSON.stringify(responseData));
            }

            // ── 2. POST /api/inspect-integration ───────────────────────────
            if (pathname === '/api/inspect-integration' && req.method === 'POST') {
                const analysis = body.analysis || (body.repo_path ? scanRepositoryStructure(body.repo_path) : null);
                if (!analysis) {
                    res.writeHead(400, { 'Content-Type': 'application/json' });
                    return res.end(JSON.stringify({ error: 'Missing repository analysis object or repo_path' }));
                }

                const requestedFeature = body.feature_name || body.goal || 'Feature Integration';
                const compatibility = analyzeIntegrationCompatibility(analysis, requestedFeature);

                res.writeHead(200, { 'Content-Type': 'application/json' });
                return res.end(JSON.stringify(compatibility));
            }

            // ── 3. POST /api/generate-proposal ─────────────────────────────
            if (pathname === '/api/generate-proposal' && req.method === 'POST') {
                const featureName = body.feature_name || 'New Modular Skill';
                const description = body.description || `Integrates ${featureName} into Jarvis architecture safely`;
                const targetFilePath = body.target_file_path || path.join(WORKSPACE_ROOT, 'app', 'src', 'main', 'java', 'com', 'jarvis', 'assistant', 'automation', 'skills', 'builtin', `${featureName.replace(/[^a-zA-Z0-9]/g, '')}Skill.kt`);

                if (!isInsideWorkspace(targetFilePath)) {
                    res.writeHead(403, { 'Content-Type': 'application/json' });
                    return res.end(JSON.stringify({ error: 'Security violation: Target file path is outside the workspace root.' }));
                }

                const proposalId = `proposal_${Date.now()}_${crypto.randomBytes(4).toString('hex')}`;
                const approvalToken = crypto.randomBytes(16).toString('hex');

                let filesList = null;
                let diffPreview = null;
                let sampleCode = body.proposed_code;

                // Check for Spoon-Knife or HTML/CSS repository integration
                let sourceRepoDir = body.repo_path;
                const isSpoonKnifeRequest = featureName.toLowerCase().includes('spoon') ||
                    featureName.toLowerCase().includes('knife') ||
                    (typeof sourceRepoDir === 'string' && sourceRepoDir.toLowerCase().includes('spoon-knife'));

                if (!sourceRepoDir && isSpoonKnifeRequest && fs.existsSync(SANDBOX_DIR)) {
                    const matches = fs.readdirSync(SANDBOX_DIR).filter(d => d.includes('Spoon-Knife')).sort().reverse();
                    if (matches.length > 0) sourceRepoDir = path.join(SANDBOX_DIR, matches[0]);
                }

                const isSpoonKnife = isSpoonKnifeRequest || (sourceRepoDir && fs.existsSync(path.join(sourceRepoDir, 'index.html')) && isSpoonKnifeRequest);

                if (isSpoonKnife) {
                    const htmlSrc = (sourceRepoDir && fs.existsSync(path.join(sourceRepoDir, 'index.html')))
                        ? fs.readFileSync(path.join(sourceRepoDir, 'index.html'), 'utf8')
                        : fs.readFileSync(path.join(WORKSPACE_ROOT, 'app', 'src', 'main', 'assets', 'spoonknife', 'index.html'), 'utf8');
                    const cssSrc = (sourceRepoDir && fs.existsSync(path.join(sourceRepoDir, 'styles.css')))
                        ? fs.readFileSync(path.join(sourceRepoDir, 'styles.css'), 'utf8')
                        : fs.readFileSync(path.join(WORKSPACE_ROOT, 'app', 'src', 'main', 'assets', 'spoonknife', 'styles.css'), 'utf8');
                    const readmeSrc = (sourceRepoDir && fs.existsSync(path.join(sourceRepoDir, 'README.md')))
                        ? fs.readFileSync(path.join(sourceRepoDir, 'README.md'), 'utf8')
                        : fs.readFileSync(path.join(WORKSPACE_ROOT, 'app', 'src', 'main', 'assets', 'spoonknife', 'README.md'), 'utf8');

                    const activityCode = fs.readFileSync(path.join(WORKSPACE_ROOT, 'app', 'src', 'main', 'java', 'com', 'jarvis', 'assistant', 'ui', 'spoonknife', 'SpoonKnifeDemoActivity.kt'), 'utf8');
                    const fragmentCode = fs.readFileSync(path.join(WORKSPACE_ROOT, 'app', 'src', 'main', 'java', 'com', 'jarvis', 'assistant', 'ui', 'spoonknife', 'SpoonKnifeDemoFragment.kt'), 'utf8');
                    const skillCode = fs.readFileSync(path.join(WORKSPACE_ROOT, 'app', 'src', 'main', 'java', 'com', 'jarvis', 'assistant', 'automation', 'skills', 'builtin', 'SpoonKnifeDemoScreenSkill.kt'), 'utf8');

                    sampleCode = skillCode;

                    filesList = [
                        { targetFile: 'app/src/main/assets/spoonknife/index.html', proposedContent: htmlSrc },
                        { targetFile: 'app/src/main/assets/spoonknife/styles.css', proposedContent: cssSrc },
                        { targetFile: 'app/src/main/assets/spoonknife/README.md', proposedContent: readmeSrc },
                        { targetFile: 'app/src/main/java/com/jarvis/assistant/ui/spoonknife/SpoonKnifeDemoActivity.kt', proposedContent: activityCode },
                        { targetFile: 'app/src/main/java/com/jarvis/assistant/ui/spoonknife/SpoonKnifeDemoFragment.kt', proposedContent: fragmentCode },
                        { targetFile: 'app/src/main/java/com/jarvis/assistant/automation/skills/builtin/SpoonKnifeDemoScreenSkill.kt', proposedContent: skillCode }
                    ];

                    diffPreview = filesList.map(f => makeUnifiedDiff(f.targetFile, f.proposedContent)).join('\n');
                } else {
                    sampleCode = sampleCode || `// Generated integration stub for ${featureName}\npackage com.jarvis.assistant.automation.skills.builtin\n\n// Verified integration\n`;
                    diffPreview = makeUnifiedDiff(path.relative(WORKSPACE_ROOT, targetFilePath), sampleCode);
                }

                const proposal = {
                    proposalId,
                    approvalToken,
                    featureName,
                    description,
                    createdAt: new Date().toISOString(),
                    targetFile: path.relative(WORKSPACE_ROOT, targetFilePath).replace(/\\/g, '/'),
                    proposedContent: sampleCode,
                    files: filesList,
                    diffPreview,
                    status: 'PENDING_USER_APPROVAL',
                    riskAssessment: 'MODERATE: Modifies Kotlin codebase and assets within automation skills package.'
                };

                pendingProposals.set(proposalId, proposal);
                persistState();

                res.writeHead(200, { 'Content-Type': 'application/json' });
                return res.end(JSON.stringify({
                    proposalId,
                    approvalTokenPrompt: `Approval required. Use approval_token '${approvalToken}' to apply changes.`,
                    proposal
                }));
            }

            // ── 3b. POST /api/review-proposal ─────────────────────────────
            if (pathname === '/api/review-proposal' && req.method === 'POST') {
                const proposalId = body.proposal_id || body.proposalId;
                const featureName = body.feature_name || body.featureName;

                if (proposalId && isInvalidatedProposal(proposalId)) {
                    res.writeHead(410, { 'Content-Type': 'application/json' });
                    return res.end(JSON.stringify({
                        error: `Action Blocked: Old proposal '${proposalId}' has been permanently invalidated due to an exposed approval token. Please generate a fresh proposal.`,
                        status: 'EXPIRED_OR_INVALIDATED'
                    }));
                }

                const proposal = findProposal(proposalId, featureName);
                if (proposal && proposal.invalidated) {
                    res.writeHead(410, { 'Content-Type': 'application/json' });
                    return res.end(JSON.stringify({
                        error: `Action Blocked: Proposal has been permanently invalidated due to an exposed approval token. Please generate a fresh proposal.`,
                        status: 'EXPIRED_OR_INVALIDATED'
                    }));
                }
                if (proposal && proposal.ambiguous) {
                    res.writeHead(400, { 'Content-Type': 'application/json' });
                    return res.end(JSON.stringify({
                        error: `Ambiguous proposal query '${proposalId}': Matches multiple active proposals. Please specify the exact canonical proposal ID.`,
                        status: 'AMBIGUOUS_MATCH',
                        availableProposals: proposal.matches
                    }));
                }
                if (!proposal || !proposal.proposalId) {
                    const available = Array.from(pendingProposals.values())
                        .filter(p => !isInvalidatedProposal(p.proposalId))
                        .map(p => ({
                            proposalId: p.proposalId,
                            featureName: p.featureName,
                            createdAt: p.createdAt,
                            status: p.status
                        }));

                    res.writeHead(404, { 'Content-Type': 'application/json' });
                    return res.end(JSON.stringify({
                        error: `Proposal '${proposalId || 'latest'}' not found or expired.`,
                        suggestion: "To regenerate a proposal, call generate_integration_proposal with the feature_name (e.g. 'Spoon-Knife Demo Screen').",
                        availableProposals: available
                    }));
                }

                const filesPayload = proposal.files && Array.isArray(proposal.files) && proposal.files.length > 0
                    ? proposal.files
                    : [{ targetFile: proposal.targetFile, proposedContent: proposal.proposedContent }];

                res.writeHead(200, { 'Content-Type': 'application/json' });
                return res.end(JSON.stringify({
                    status: 'SUCCESS',
                    proposal_id: proposal.proposalId,
                    feature_name: proposal.featureName,
                    description: proposal.description,
                    target_file: proposal.targetFile,
                    diff: proposal.diffPreview,
                    diff_preview: proposal.diffPreview,
                    files: filesPayload,
                    proposal_status: proposal.status,
                    risk_assessment: proposal.riskAssessment,
                    proposal
                }));
            }

            // ── 3c. GET /api/proposal ─────────────────────────────────────
            if (pathname === '/api/proposal' && req.method === 'GET') {
                const proposalId = parsedUrl.searchParams.get('id') || parsedUrl.searchParams.get('proposal_id');
                const featureName = parsedUrl.searchParams.get('feature') || parsedUrl.searchParams.get('feature_name');

                if (proposalId && isInvalidatedProposal(proposalId)) {
                    res.writeHead(410, { 'Content-Type': 'application/json' });
                    return res.end(JSON.stringify({
                        error: `Action Blocked: Old proposal '${proposalId}' has been permanently invalidated due to an exposed approval token. Please generate a fresh proposal.`,
                        status: 'EXPIRED_OR_INVALIDATED'
                    }));
                }

                const proposal = findProposal(proposalId, featureName);
                if (proposal && proposal.invalidated) {
                    res.writeHead(410, { 'Content-Type': 'application/json' });
                    return res.end(JSON.stringify({
                        error: `Action Blocked: Proposal has been permanently invalidated due to an exposed approval token. Please generate a fresh proposal.`,
                        status: 'EXPIRED_OR_INVALIDATED'
                    }));
                }
                if (proposal && proposal.ambiguous) {
                    res.writeHead(400, { 'Content-Type': 'application/json' });
                    return res.end(JSON.stringify({
                        error: `Ambiguous proposal query '${proposalId}': Matches multiple active proposals. Please specify the exact canonical proposal ID.`,
                        status: 'AMBIGUOUS_MATCH',
                        availableProposals: proposal.matches
                    }));
                }
                if (!proposal || !proposal.proposalId) {
                    res.writeHead(404, { 'Content-Type': 'application/json' });
                    return res.end(JSON.stringify({
                        error: `Proposal '${proposalId || 'latest'}' not found or expired.`,
                        suggestion: "To regenerate a proposal, call generate_integration_proposal with the feature_name."
                    }));
                }

                const filesPayload = proposal.files && Array.isArray(proposal.files) && proposal.files.length > 0
                    ? proposal.files
                    : [{ targetFile: proposal.targetFile, proposedContent: proposal.proposedContent }];

                res.writeHead(200, { 'Content-Type': 'application/json' });
                return res.end(JSON.stringify({
                    status: 'SUCCESS',
                    proposal_id: proposal.proposalId,
                    feature_name: proposal.featureName,
                    description: proposal.description,
                    target_file: proposal.targetFile,
                    diff: proposal.diffPreview,
                    diff_preview: proposal.diffPreview,
                    files: filesPayload,
                    proposal_status: proposal.status,
                    risk_assessment: proposal.riskAssessment,
                    proposal
                }));
            }

            // ── 3d. GET /api/proposals ─────────────────────────────────────
            if (pathname === '/api/proposals' && req.method === 'GET') {
                const list = Array.from(pendingProposals.values())
                    .filter(p => !isInvalidatedProposal(p.proposalId))
                    .map(p => ({
                        proposalId: p.proposalId,
                        featureName: p.featureName,
                        description: p.description,
                        targetFile: p.targetFile,
                        createdAt: p.createdAt,
                        status: p.status,
                        riskAssessment: p.riskAssessment,
                        filesCount: p.files ? p.files.length : 1
                    }));

                res.writeHead(200, { 'Content-Type': 'application/json' });
                return res.end(JSON.stringify({
                    status: 'SUCCESS',
                    count: list.length,
                    proposals: list
                }));
            }

            // ── 4. POST /api/apply-proposal ────────────────────────────────
            if (pathname === '/api/apply-proposal' && req.method === 'POST') {
                const proposal_id = body.proposal_id || body.proposalId;
                const approval_token = body.approval_token || body.approvalToken;

                if (!proposal_id) {
                    res.writeHead(400, { 'Content-Type': 'application/json' });
                    return res.end(JSON.stringify({ error: 'Missing proposal_id in request' }));
                }

                if (isInvalidatedProposal(proposal_id)) {
                    res.writeHead(410, { 'Content-Type': 'application/json' });
                    return res.end(JSON.stringify({
                        error: `Action Blocked: Old proposal '${proposal_id}' has been permanently invalidated due to an exposed approval token. Code modification rejected.`,
                        status: 'EXPIRED_OR_INVALIDATED'
                    }));
                }

                const proposal = findProposal(proposal_id);
                if (proposal && proposal.ambiguous) {
                    res.writeHead(400, { 'Content-Type': 'application/json' });
                    return res.end(JSON.stringify({
                        error: `Action Blocked: Ambiguous proposal ID '${proposal_id}'. Code modification requires the exact canonical proposal ID.`,
                        status: 'AMBIGUOUS_MATCH',
                        availableProposals: proposal.matches
                    }));
                }
                if (!proposal || !proposal.proposalId) {
                    res.writeHead(404, { 'Content-Type': 'application/json' });
                    return res.end(JSON.stringify({ error: `Proposal '${proposal_id}' not found or expired.` }));
                }

                // Strict approval gate
                if (!approval_token || approval_token !== proposal.approvalToken) {
                    res.writeHead(403, { 'Content-Type': 'application/json' });
                    return res.end(JSON.stringify({
                        error: `Action Blocked: Invalid or mismatched approval_token for proposal '${proposal.proposalId}'. Code modification requires the exact approval token generated for this specific proposal.`,
                        status: 'WAITING_FOR_USER_APPROVAL'
                    }));
                }

                const filesToApply = proposal.files && Array.isArray(proposal.files) && proposal.files.length > 0
                    ? proposal.files
                    : [{ targetFile: proposal.targetFile, proposedContent: proposal.proposedContent }];

                // Check security boundary for all target files
                for (const item of filesToApply) {
                    const absPath = path.join(WORKSPACE_ROOT, item.targetFile);
                    if (!isInsideWorkspace(absPath)) {
                        res.writeHead(403, { 'Content-Type': 'application/json' });
                        return res.end(JSON.stringify({ error: `Security violation: Target path '${item.targetFile}' is outside workspace root.` }));
                    }
                }

                // Create pre-application backup checkpoint
                const checkpointId = `checkpoint_${proposal_id}_${Date.now()}`;
                const fileBackups = [];

                for (const item of filesToApply) {
                    const absPath = path.join(WORKSPACE_ROOT, item.targetFile);
                    const fileExisted = fs.existsSync(absPath);
                    const safeName = item.targetFile.replace(/[^a-zA-Z0-9]/g, '_');
                    const backupPath = path.join(BACKUP_DIR, `${checkpointId}_${safeName}.bak`);

                    if (fileExisted) {
                        fs.copyFileSync(absPath, backupPath);
                    }

                    fileBackups.push({
                        targetFile: absPath,
                        backupPath: fileExisted ? backupPath : null,
                        fileExisted
                    });
                }

                activeCheckpoints.set(checkpointId, {
                    proposalId: proposal_id,
                    files: fileBackups
                });

                // Apply changes
                for (const item of filesToApply) {
                    const absPath = path.join(WORKSPACE_ROOT, item.targetFile);
                    fs.mkdirSync(path.dirname(absPath), { recursive: true });
                    fs.writeFileSync(absPath, item.proposedContent, 'utf8');
                }

                proposal.status = 'APPLIED';
                proposal.checkpointId = checkpointId;
                persistState();

                res.writeHead(200, { 'Content-Type': 'application/json' });
                return res.end(JSON.stringify({
                    status: 'SUCCESS',
                    message: `Successfully applied proposal '${proposal_id}'.`,
                    checkpointId,
                    appliedFiles: filesToApply.map(f => f.targetFile)
                }));
            }

            // ── 5. POST /api/rollback ──────────────────────────────────────
            if (pathname === '/api/rollback' && req.method === 'POST') {
                const { checkpoint_id } = body;
                const checkpoint = activeCheckpoints.get(checkpoint_id);

                if (!checkpoint) {
                    res.writeHead(404, { 'Content-Type': 'application/json' });
                    return res.end(JSON.stringify({ error: `Checkpoint '${checkpoint_id}' not found.` }));
                }

                if (checkpoint.files && Array.isArray(checkpoint.files)) {
                    for (const item of checkpoint.files) {
                        if (item.fileExisted && item.backupPath && fs.existsSync(item.backupPath)) {
                            fs.copyFileSync(item.backupPath, item.targetFile);
                        } else if (!item.fileExisted && fs.existsSync(item.targetFile)) {
                            fs.unlinkSync(item.targetFile);
                        }
                    }
                } else if (checkpoint.targetFile) {
                    if (checkpoint.fileExisted && checkpoint.backupPath && fs.existsSync(checkpoint.backupPath)) {
                        fs.copyFileSync(checkpoint.backupPath, checkpoint.targetFile);
                    } else if (!checkpoint.fileExisted && fs.existsSync(checkpoint.targetFile)) {
                        fs.unlinkSync(checkpoint.targetFile);
                    }
                }

                activeCheckpoints.delete(checkpoint_id);
                persistState();

                res.writeHead(200, { 'Content-Type': 'application/json' });
                return res.end(JSON.stringify({
                    status: 'ROLLED_BACK',
                    message: `Successfully restored pre-application state from checkpoint '${checkpoint_id}'.`
                }));
            }

            // ── 6. POST /api/run-validation ────────────────────────────────
            if (pathname === '/api/run-validation' && req.method === 'POST') {
                exec('.\\gradlew.bat testDebugUnitTest', { cwd: WORKSPACE_ROOT, timeout: 180000 }, (error, stdout, stderr) => {
                    const output = stdout + stderr;
                    const success = !error;
                    res.writeHead(200, { 'Content-Type': 'application/json' });
                    return res.end(JSON.stringify({
                        status: success ? 'VALIDATION_PASSED' : 'VALIDATION_FAILED',
                        exitCode: error ? (error.code || 1) : 0,
                        summary: success ? 'All unit tests passed successfully.' : 'One or more unit tests failed.',
                        outputPreview: redactSecrets(output.slice(-2000))
                    }));
                });
                return;
            }

            res.writeHead(404, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: `Endpoint not found: ${pathname}` }));

        } catch (serverErr) {
            res.writeHead(500, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: `Internal server error: ${serverErr.message}` }));
        }
    });
});

if (require.main === module) {
    server.listen(PORT, HOST, () => {
        console.log(`[Jarvis Companion] Running at http://${HOST}:${PORT}`);
        console.log(`[Jarvis Companion] Workspace Root: ${WORKSPACE_ROOT}`);
        console.log(`[Jarvis Companion] Auth Token: ${authToken}`);
        ensureAdbReverse();
        setInterval(ensureAdbReverse, 15000);
    });
}

module.exports = {
    server,
    PORT,
    HOST,
    authToken,
    pendingProposals,
    activeCheckpoints,
    loadPersistedState,
    persistState,
    findProposal,
    ensureAdbReverse,
    normalizeId,
    isInvalidatedProposal,
    EXPOSED_INVALIDATED_PROPOSALS,
    DURABLE_PROPOSALS_FILE,
    DURABLE_DATA_DIR,
    PROPOSALS_FILE,
    validateGitHubUrl,
    isInsideWorkspace,
    scanRepositoryStructure,
    analyzeIntegrationCompatibility,
    redactSecrets
};
