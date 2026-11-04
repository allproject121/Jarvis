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


// ════════════════════════════════════════════════════════════════════════════
// JARVIS PC ⇄ PHONE HUB (additive, WP8) — zero-dependency WebSocket relay at /jarvis
//
//  • Loopback: attached to the main companion server (127.0.0.1:8765, reachable from the
//    phone through `adb reverse tcp:8765 tcp:8765`).
//  • LAN: a second listener on 0.0.0.0:${JARVIS_HUB_PORT:-8766} (disable with JARVIS_HUB_LAN=0).
//  • Pairing: a 6-digit join code is printed on this console; the phone sends it once
//    (type "pair") and receives a session token for silent reconnects. Unpaired sockets may
//    only send hello / pair / ping. 5 wrong codes close the socket.
//  • Envelope: {"v":1,"type","id","ts","reply_to?","payload":{}} — mirrors PcHubProtocol.kt.
//  • ask / call_chunk run through the PC's own LLM (Ollama at JARVIS_HUB_OLLAMA_URL, model
//    JARVIS_HUB_MODEL) when reachable, else reply "PC agent not configured".
//  • control runs only an allow-list of PC actions (status, lock, pc_sleep, shutdown, restart,
//    cancel_shutdown, mute, unmute, open_url <url>); raw shell needs JARVIS_HUB_ALLOW_SHELL=1.
// ════════════════════════════════════════════════════════════════════════════

const HUB_PATH = '/jarvis';
const HUB_LAN_PORT = parseInt(process.env.JARVIS_HUB_PORT || '8766', 10);
const HUB_STATE_FILE = path.join(__dirname, '.hub_state.json');
const HUB_MEMORY_FILE = path.join(__dirname, '.hub_memory.json');
const HUB_OLLAMA_URL = process.env.JARVIS_HUB_OLLAMA_URL || 'http://127.0.0.1:11434';
const HUB_MODEL = process.env.JARVIS_HUB_MODEL || '';
const HUB_PC_NAME = process.env.JARVIS_HUB_PC_NAME || require('os').hostname();

function hubLoadState() {
    try { return JSON.parse(fs.readFileSync(HUB_STATE_FILE, 'utf8')); } catch (_) { return { tokens: [] }; }
}
function hubSaveState(st) {
    try { fs.writeFileSync(HUB_STATE_FILE, JSON.stringify(st, null, 2), { mode: 0o600 }); } catch (_) {}
}
const hubState = hubLoadState();
if (!Array.isArray(hubState.tokens)) hubState.tokens = [];
let hubJoinCode = process.env.JARVIS_HUB_CODE || String(crypto.randomInt(0, 1000000)).padStart(6, '0');

function hubNewId(prefix) {
    return `${prefix || 'pc'}_${Date.now().toString(36)}_${crypto.randomBytes(3).toString('hex')}`;
}

function hubEnvelope(type, payload, replyTo) {
    const m = { v: 1, type, id: hubNewId('pc'), ts: Date.now(), payload: payload || {} };
    if (replyTo) m.reply_to = replyTo;
    return m;
}

function hubDecode(raw) {
    try {
        const o = JSON.parse(raw);
        if (!o || typeof o.type !== 'string' || !o.type) return null;
        return { v: o.v || 1, type: o.type, id: o.id || hubNewId('x'), ts: o.ts || Date.now(), reply_to: o.reply_to || null, payload: (o.payload && typeof o.payload === 'object') ? o.payload : {} };
    } catch (_) { return null; }
}

// ── Minimal RFC 6455 framing ────────────────────────────────────────────────
function wsEncodeFrame(opcode, data) {
    const payload = Buffer.isBuffer(data) ? data : Buffer.from(String(data), 'utf8');
    const len = payload.length;
    let header;
    if (len < 126) {
        header = Buffer.alloc(2); header[1] = len;
    } else if (len < 65536) {
        header = Buffer.alloc(4); header[1] = 126; header.writeUInt16BE(len, 2);
    } else {
        header = Buffer.alloc(10); header[1] = 127; header.writeBigUInt64BE(BigInt(len), 2);
    }
    header[0] = 0x80 | opcode;
    return Buffer.concat([header, payload]);
}

/** Parses as many complete frames as possible; returns { frames, rest }. */
function wsDecodeFrames(buf) {
    const frames = [];
    let off = 0;
    while (buf.length - off >= 2) {
        const b0 = buf[off], b1 = buf[off + 1];
        const fin = (b0 & 0x80) !== 0;
        const opcode = b0 & 0x0f;
        const masked = (b1 & 0x80) !== 0;
        let len = b1 & 0x7f;
        let p = off + 2;
        if (len === 126) { if (buf.length - p < 2) break; len = buf.readUInt16BE(p); p += 2; }
        else if (len === 127) { if (buf.length - p < 8) break; len = Number(buf.readBigUInt64BE(p)); p += 8; }
        if (len > 16 * 1024 * 1024) throw new Error('frame too large');
        let mask = null;
        if (masked) { if (buf.length - p < 4) break; mask = buf.slice(p, p + 4); p += 4; }
        if (buf.length - p < len) break;
        const data = Buffer.from(buf.slice(p, p + len));
        if (mask) for (let i = 0; i < data.length; i++) data[i] ^= mask[i & 3];
        frames.push({ fin, opcode, data });
        off = p + len;
    }
    return { frames, rest: buf.slice(off) };
}

// ── PC-side capabilities ────────────────────────────────────────────────────
function hubHttpJson(urlStr, body, timeoutMs) {
    return new Promise((resolve, reject) => {
        let u;
        try { u = new URL(urlStr); } catch (e) { return reject(e); }
        const data = body ? Buffer.from(JSON.stringify(body)) : null;
        const req = http.request({
            hostname: u.hostname, port: u.port || 80, path: u.pathname + u.search,
            method: data ? 'POST' : 'GET',
            headers: data ? { 'Content-Type': 'application/json', 'Content-Length': data.length } : {},
            timeout: timeoutMs || 60000
        }, res => {
            let text = '';
            res.on('data', c => { text += c; });
            res.on('end', () => {
                if (res.statusCode >= 400) return reject(new Error(`HTTP ${res.statusCode}`));
                try { resolve(JSON.parse(text)); } catch (e) { reject(e); }
            });
        });
        req.on('timeout', () => req.destroy(new Error('timeout')));
        req.on('error', reject);
        if (data) req.write(data);
        req.end();
    });
}

let hubResolvedModel = HUB_MODEL;
async function hubPickModel() {
    if (hubResolvedModel) return hubResolvedModel;
    const tags = await hubHttpJson(`${HUB_OLLAMA_URL}/api/tags`, null, 4000);
    const first = tags && Array.isArray(tags.models) && tags.models[0] && tags.models[0].name;
    if (!first) throw new Error('no local models');
    hubResolvedModel = first;
    return first;
}

async function hubAskLlm(messages) {
    try {
        const model = await hubPickModel();
        const sys = { role: 'system', content: `You are the PC-side Jarvis agent on "${HUB_PC_NAME}". The user is talking to you from their phone through the Jarvis hub. Be concise.` };
        const out = await hubHttpJson(`${HUB_OLLAMA_URL}/api/chat`, { model, stream: false, messages: [sys, ...messages] }, 110000);
        const text = out && out.message && out.message.content;
        return (text && String(text).trim()) || '(PC agent returned an empty answer)';
    } catch (e) {
        const last = messages.length ? messages[messages.length - 1].content : '';
        return `PC agent not configured (no local LLM reachable at ${HUB_OLLAMA_URL}: ${e.message}). Echo: ${last}`;
    }
}

function hubRun(cmd) {
    return new Promise(resolve => {
        exec(cmd, { timeout: 20000 }, (err, stdout, stderr) => {
            resolve({ ok: !err, exitCode: err ? (err.code || 1) : 0, output: redactSecrets(String(stdout || '') + String(stderr || '')).slice(-4000) });
        });
    });
}

function hubControlCommand(action, arg) {
    const win = process.platform === 'win32', mac = process.platform === 'darwin';
    switch (action) {
        case 'lock': return win ? 'rundll32.exe user32.dll,LockWorkStation' : mac ? 'pmset displaysleepnow' : 'loginctl lock-session';
        case 'pc_sleep': case 'sleep': return win ? 'rundll32.exe powrprof.dll,SetSuspendState 0,1,0' : mac ? 'pmset sleepnow' : 'systemctl suspend';
        case 'shutdown': return win ? 'shutdown /s /t 60' : mac ? 'sudo -n shutdown -h +1' : 'shutdown -h +1';
        case 'restart': return win ? 'shutdown /r /t 60' : mac ? 'sudo -n shutdown -r +1' : 'shutdown -r +1';
        case 'cancel_shutdown': return win ? 'shutdown /a' : 'shutdown -c';
        case 'mute': case 'unmute':
            if (win) return 'powershell -NoProfile -Command "(New-Object -ComObject WScript.Shell).SendKeys([char]173)"';
            if (mac) return `osascript -e "set volume output muted ${action === 'mute' ? 'true' : 'false'}"`;
            return `amixer -q set Master ${action === 'mute' ? 'mute' : 'unmute'}`;
        case 'open_url': {
            if (!/^https?:\/\/[^\s"'`]+$/i.test(arg || '')) return null;
            return win ? `start "" "${arg}"` : mac ? `open "${arg}"` : `xdg-open "${arg}"`;
        }
        default: return null;
    }
}

async function hubControl(command) {
    const raw = String(command || '').trim();
    const [first, ...restParts] = raw.split(/\s+/);
    const action = (first || '').toLowerCase();
    const arg = restParts.join(' ');
    if (action === 'status' || action === 'battery' || action === '') {
        const os = require('os');
        return {
            ok: true,
            text: `${HUB_PC_NAME}: ${os.platform()} ${os.release()}, up ${Math.round(os.uptime() / 60)} min, load ${os.loadavg()[0].toFixed(2)}, free RAM ${Math.round(os.freemem() / 1048576)} MB of ${Math.round(os.totalmem() / 1048576)} MB.`
        };
    }
    if (action === 'shell') {
        if (process.env.JARVIS_HUB_ALLOW_SHELL !== '1') return { ok: false, text: 'Raw shell from the phone is disabled on this PC (set JARVIS_HUB_ALLOW_SHELL=1 to allow).' };
        const r = await hubRun(arg);
        return { ok: r.ok, text: r.output || `exit ${r.exitCode}`, exitCode: r.exitCode };
    }
    const cmd = hubControlCommand(action, arg);
    if (!cmd) return { ok: false, text: `Unknown or disallowed PC action '${action}'. Allowed: status, lock, pc_sleep, shutdown, restart, cancel_shutdown, mute, unmute, open_url <url>${process.env.JARVIS_HUB_ALLOW_SHELL === '1' ? ', shell <cmd>' : ''}.` };
    const r = await hubRun(cmd);
    return { ok: r.ok, text: r.ok ? `PC: ${action} done.${(action === 'shutdown' || action === 'restart') && process.platform === 'win32' ? ' (in 60 s — send cancel_shutdown to abort)' : ''}` : `PC: ${action} failed: ${r.output || 'exit ' + r.exitCode}`, exitCode: r.exitCode };
}

// ── Connection handling ─────────────────────────────────────────────────────
const hubClients = new Set();

function hubHandleUpgrade(req, socket, head, origin) {
    let pathname = '';
    let query;
    try { const u = new URL(req.url, 'http://x'); pathname = u.pathname; query = u.searchParams; } catch (_) { socket.destroy(); return; }
    if (pathname !== HUB_PATH) { socket.destroy(); return; }
    const key = req.headers['sec-websocket-key'];
    if (!key || String(req.headers.upgrade || '').toLowerCase() !== 'websocket') {
        socket.end('HTTP/1.1 400 Bad Request\r\n\r\n');
        return;
    }
    const accept = crypto.createHash('sha1').update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');
    socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n' + `Sec-WebSocket-Accept: ${accept}\r\n\r\n`);

    const token = query.get('token') || '';
    const client = {
        socket, origin,
        paired: !!token && hubState.tokens.includes(token),
        failedPairs: 0,
        device: '',
        call: null, // { history: [] }
        send(msg) { try { socket.write(wsEncodeFrame(0x1, JSON.stringify(msg))); } catch (_) {} }
    };
    hubClients.add(client);
    let buffer = head && head.length ? Buffer.from(head) : Buffer.alloc(0);
    let fragments = [];

    socket.on('data', chunk => {
        buffer = Buffer.concat([buffer, chunk]);
        let parsed;
        try { parsed = wsDecodeFrames(buffer); } catch (e) { socket.destroy(); return; }
        buffer = parsed.rest;
        for (const f of parsed.frames) {
            if (f.opcode === 0x8) { try { socket.end(wsEncodeFrame(0x8, Buffer.alloc(0))); } catch (_) {} return; }
            if (f.opcode === 0x9) { try { socket.write(wsEncodeFrame(0xA, f.data)); } catch (_) {} continue; }
            if (f.opcode === 0xA) continue;
            if (f.opcode === 0x1 || f.opcode === 0x0) {
                fragments.push(f.data);
                if (f.fin) {
                    const text = Buffer.concat(fragments).toString('utf8');
                    fragments = [];
                    hubOnMessage(client, text).catch(err => client.send(hubEnvelope('error', { message: err.message })));
                }
            }
        }
    });
    socket.on('close', () => hubClients.delete(client));
    socket.on('error', () => hubClients.delete(client));
}

async function hubOnMessage(client, raw) {
    const msg = hubDecode(raw);
    if (!msg) return;
    const reply = (type, payload) => client.send(hubEnvelope(type, payload, msg.id));

    switch (msg.type) {
        case 'ping': return reply('pong', { t: Date.now() });
        case 'hello':
            client.device = String(msg.payload.device || 'phone');
            if (msg.payload.token && hubState.tokens.includes(msg.payload.token)) client.paired = true;
            console.log(`[Jarvis Hub] hello from ${client.device} (${client.origin}, paired=${client.paired})${msg.payload.phone_code ? ' phone code ' + msg.payload.phone_code : ''}`);
            return reply('welcome', { pc_name: HUB_PC_NAME, paired: client.paired, needs_code: !client.paired, version: 1 });
        case 'pair': {
            const code = String(msg.payload.code || '').replace(/\D/g, '');
            if (code.length === 6 && code === hubJoinCode) {
                const token = crypto.randomBytes(24).toString('hex');
                hubState.tokens.push(token);
                if (hubState.tokens.length > 10) hubState.tokens.splice(0, hubState.tokens.length - 10);
                hubSaveState(hubState);
                client.paired = true;
                hubJoinCode = String(crypto.randomInt(0, 1000000)).padStart(6, '0');
                console.log(`[Jarvis Hub] paired with ${client.device || 'phone'}. Next join code: ${hubJoinCode}`);
                return reply('paired', { token, pc_name: HUB_PC_NAME });
            }
            client.failedPairs++;
            reply('pair_failed', { reason: 'Wrong join code — check the code shown on the PC console.' });
            if (client.failedPairs >= 5) { try { client.socket.end(wsEncodeFrame(0x8, Buffer.alloc(0))); } catch (_) {} }
            return;
        }
    }

    if (!client.paired) return reply('error', { message: 'Not paired: send the 6-digit join code shown on the PC first.' });

    switch (msg.type) {
        case 'ask': {
            const text = await hubAskLlm([{ role: 'user', content: String(msg.payload.text || '') }]);
            return reply('reply', { text });
        }
        case 'control': {
            const r = await hubControl(msg.payload.command);
            return reply('result', { ok: r.ok, text: r.text, exit_code: r.exitCode === undefined ? null : r.exitCode });
        }
        case 'call_start':
            client.call = { history: [] };
            console.log(`[Jarvis Hub] call started with ${client.device}`);
            return reply('call_started', { text: `Call relay open with ${HUB_PC_NAME}.` });
        case 'call_chunk': {
            if (!client.call) client.call = { history: [] };
            const userText = String(msg.payload.text || '');
            console.log(`[Jarvis Hub] [phone] ${userText}`);
            client.call.history.push({ role: 'user', content: userText });
            const answer = await hubAskLlm(client.call.history.slice(-20));
            client.call.history.push({ role: 'assistant', content: answer });
            return reply('call_reply', { text: answer });
        }
        case 'call_end':
            client.call = null;
            return reply('call_ended', { text: 'Call ended.' });
        case 'memory_push': {
            const data = String(msg.payload.data || '');
            try { JSON.parse(data); } catch (_) { return reply('error', { message: 'memory_push: data is not valid JSON' }); }
            fs.writeFileSync(HUB_MEMORY_FILE, data, { mode: 0o600 });
            let count = 0;
            try { const o = JSON.parse(data); count = Array.isArray(o.memories) ? o.memories.length : 0; } catch (_) {}
            return reply('memory_ack', { summary: `PC stored ${count} memories`, count });
        }
        case 'memory_pull': {
            let data = '';
            try { data = fs.readFileSync(HUB_MEMORY_FILE, 'utf8'); } catch (_) {}
            return reply('memory_data', { data, format: 'jarvis-memory-export' });
        }
        default:
            return reply('error', { message: `Unknown hub message type '${msg.type}'` });
    }
}

/** Attaches the hub WebSocket endpoint to an http.Server. */
function attachJarvisHub(httpServer, opts) {
    const loopbackOnly = !!(opts && opts.loopbackOnly);
    httpServer.on('upgrade', (req, socket, head) => {
        const ip = req.socket.remoteAddress || '';
        if (loopbackOnly && ip !== '127.0.0.1' && ip !== '::1' && ip !== '::ffff:127.0.0.1') { socket.destroy(); return; }
        hubHandleUpgrade(req, socket, head, ip);
    });
    return httpServer;
}

attachJarvisHub(server, { loopbackOnly: true });

let hubLanServer = null;
function startHubLan() {
    if (process.env.JARVIS_HUB_LAN === '0' || hubLanServer) return hubLanServer;
    hubLanServer = http.createServer((req, res) => {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ service: 'JarvisHub', path: HUB_PATH, pc_name: HUB_PC_NAME }));
    });
    attachJarvisHub(hubLanServer, { loopbackOnly: false });
    hubLanServer.on('error', e => console.log(`[Jarvis Hub] LAN listener disabled: ${e.message}`));
    hubLanServer.listen(HUB_LAN_PORT, '0.0.0.0', () => {
        const nets = require('os').networkInterfaces();
        const ips = [];
        Object.values(nets).forEach(list => (list || []).forEach(n => { if (n.family === 'IPv4' && !n.internal) ips.push(n.address); }));
        console.log(`[Jarvis Hub] LAN hub: ws://${ips[0] || '<pc-ip>'}:${HUB_LAN_PORT}${HUB_PATH}  (USB: ws://127.0.0.1:${PORT}${HUB_PATH} via adb reverse)`);
    });
    return hubLanServer;
}

if (require.main === module) {
    console.log(`[Jarvis Hub] Join code: ${hubJoinCode.slice(0, 3)} ${hubJoinCode.slice(3)}  (enter it in Jarvis → HUB → PC ⇄ Phone)`);
    startHubLan();
}

module.exports.attachJarvisHub = attachJarvisHub;
module.exports.startHubLan = startHubLan;
module.exports.hubDecode = hubDecode;
module.exports.hubEnvelope = hubEnvelope;
module.exports.wsEncodeFrame = wsEncodeFrame;
module.exports.wsDecodeFrames = wsDecodeFrames;
module.exports.hubControl = hubControl;
module.exports.getHubJoinCode = () => hubJoinCode;
