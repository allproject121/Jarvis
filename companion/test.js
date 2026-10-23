/**
 * Test suite for Jarvis Laptop Companion Service
 */

const assert = require('assert');
const {
    validateGitHubUrl,
    isInsideWorkspace,
    scanRepositoryStructure,
    analyzeIntegrationCompatibility,
    redactSecrets
} = require('./server');
const path = require('path');
const fs = require('fs');

console.log('[Companion Tests] Running unit tests...');

// 1. GitHub URL Validation Tests
console.log('-> Testing GitHub URL Validation');
assert.strictEqual(validateGitHubUrl('https://github.com/facebook/react').valid, true);
assert.strictEqual(validateGitHubUrl('https://github.com/torvalds/linux.git').valid, true);
assert.strictEqual(validateGitHubUrl('https://github.com/google/guava/').valid, true);

// Invalid protocols & malformed URLs
assert.strictEqual(validateGitHubUrl('http://github.com/owner/repo').valid, false);
assert.strictEqual(validateGitHubUrl('git@github.com:owner/repo.git').valid, false);
assert.strictEqual(validateGitHubUrl('file:///etc/passwd').valid, false);
assert.strictEqual(validateGitHubUrl('ftp://example.com/repo').valid, false);
assert.strictEqual(validateGitHubUrl('https://malicious.com/fake/repo').valid, false);
assert.strictEqual(validateGitHubUrl('https://github.com/invalid').valid, false);
console.log('   PASSED: GitHub URL validation strictly enforces public HTTPS GitHub format.');

// 2. Workspace Jail & Boundary Tests
console.log('-> Testing Workspace Boundary Jail');
const projectRoot = path.resolve(__dirname, '..');
assert.strictEqual(isInsideWorkspace(path.join(projectRoot, 'app', 'src', 'main')), true);
assert.strictEqual(isInsideWorkspace(path.join(projectRoot, 'companion', 'server.js')), true);
assert.strictEqual(isInsideWorkspace('C:\\Windows\\System32\\cmd.exe'), false);
assert.strictEqual(isInsideWorkspace(path.join(projectRoot, '..', 'evil.txt')), false);
console.log('   PASSED: Workspace boundary strictly confines file operations to project root.');

// 3. Secret Redaction Tests
console.log('-> Testing Secret Redaction in Logs');
const rawKey = 'api_key: AIzaSyD987fakeKeyForTesting123456789';
const cleanKey = redactSecrets(rawKey);
assert.strictEqual(cleanKey.includes('AIzaSyD987fakeKeyForTesting123456789'), false);
assert.strictEqual(cleanKey.includes('[REDACTED_SECRET]'), true);

const rawToken = 'ghp_0123456789abcdef0123456789abcdef0123';
const cleanToken = redactSecrets(rawToken);
assert.strictEqual(cleanToken.includes('ghp_0123456789abcdef0123456789abcdef0123'), false);
assert.strictEqual(cleanToken.includes('[REDACTED_SECRET]'), true);
console.log('   PASSED: Secret redaction masks API tokens and credentials.');

// 4. Directory Scanning Tests
console.log('-> Testing Repository Structural Scanning');
const scanResult = scanRepositoryStructure(path.join(projectRoot, 'app'), 3);
assert.strictEqual(scanResult.totalFiles > 0, true);
assert.strictEqual(scanResult.technologies.includes('Android / Kotlin Gradle') || scanResult.technologies.includes('Android Application'), true);
console.log('   PASSED: Structural scanner successfully identifies project technologies.');

// 5. Integration Compatibility Mapping
console.log('-> Testing Integration Compatibility Mapping');
const compatibility = analyzeIntegrationCompatibility(scanResult, 'Offline Audio Player');
assert.strictEqual(compatibility.recommendedModule, 'automation/skills');
assert.strictEqual(compatibility.proposedArchitectureSteps.length > 0, true);
assert.strictEqual(compatibility.compatibleJarvisModules.length >= 4, true);
console.log('   PASSED: Integration compatibility engine maps external features to Jarvis modules.');

// 6. Pairing Token Availability
console.log('-> Testing Pairing Token Availability');
const {
    authToken,
    pendingProposals,
    activeCheckpoints,
    loadPersistedState,
    persistState,
    findProposal,
    normalizeId,
    isInvalidatedProposal,
    EXPOSED_INVALIDATED_PROPOSALS,
    DURABLE_PROPOSALS_FILE,
    server,
    PORT,
    HOST
} = require('./server');
const http = require('http');

assert.strictEqual(typeof authToken, 'string');
assert.strictEqual(authToken.length > 0, true);
console.log('   PASSED: Companion auth token is generated and non-empty.');

// 7. Comprehensive Proposal Lifecycle & Resilience Tests
console.log('-> Testing Proposal Lifecycle, Persistence, Resolution, and Security');

function post(pathUrl, body, token = authToken) {
    return new Promise((resolve, reject) => {
        const data = JSON.stringify(body);
        const req = http.request({
            hostname: HOST,
            port: PORT,
            path: pathUrl,
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Content-Length': Buffer.byteLength(data),
                ...(token ? { 'x-jarvis-token': token } : {})
            }
        }, res => {
            let raw = '';
            res.on('data', chunk => raw += chunk);
            res.on('end', () => resolve({ status: res.statusCode, data: raw ? JSON.parse(raw) : {} }));
        });
        req.on('error', reject);
        req.write(data);
        req.end();
    });
}

function get(pathUrl, token = authToken) {
    return new Promise((resolve, reject) => {
        const req = http.request({
            hostname: HOST,
            port: PORT,
            path: pathUrl,
            method: 'GET',
            headers: {
                ...(token ? { 'x-jarvis-token': token } : {})
            }
        }, res => {
            let raw = '';
            res.on('data', chunk => raw += chunk);
            res.on('end', () => resolve({ status: res.statusCode, data: raw ? JSON.parse(raw) : {} }));
        });
        req.on('error', reject);
        req.end();
    });
}

async function runAsyncTests() {
    // A. Invalidated token security: rejected at map and lookup level
    assert.strictEqual(pendingProposals.has('proposal_1792510894907_594057fd'), false);
    assert.strictEqual(isInvalidatedProposal('proposal_1792510894907_594057fd'), true);
    assert.strictEqual(isInvalidatedProposal('proposal-1792510894907'), true);
    assert.strictEqual(isInvalidatedProposal('1792510894907'), true);

    // B. Rejection of invalidated proposal via API with HTTP 410
    const invRes = await post('/api/review-proposal', { proposal_id: 'proposal_1792510894907_594057fd' });
    assert.strictEqual(invRes.status, 410);
    assert.strictEqual(invRes.data.error.includes('permanently invalidated'), true);

    const invHyphenRes = await post('/api/review-proposal', { proposal_id: 'proposal-1792510894907' });
    assert.strictEqual(invHyphenRes.status, 410);

    // C. Authentication failure on endpoints without token
    const unauthRes = await post('/api/review-proposal', { proposal_id: 'any_id' }, null);
    assert.strictEqual(unauthRes.status, 401);

    const badTokenRes = await post('/api/review-proposal', { proposal_id: 'any_id' }, 'invalid_fake_token_xyz');
    assert.strictEqual(badTokenRes.status, 401);

    // D. Generate fresh multi-file Spoon-Knife proposal
    const genRes = await post('/api/generate-proposal', {
        feature_name: 'Spoon-Knife Demo Screen',
        description: 'Integrates Spoon-Knife static HTML/CSS frontend as an optional demo screen with WebView activity and skill'
    });
    assert.strictEqual(genRes.status, 200);
    const createdId = genRes.data.proposalId;
    assert.strictEqual(createdId.startsWith('proposal_'), true);
    assert.strictEqual(Array.isArray(genRes.data.proposal.files), true);
    assert.strictEqual(genRes.data.proposal.files.length >= 4, true);

    // E. Exact ID retrieval and multi-file diff verification
    const exactReview = await post('/api/review-proposal', { proposal_id: createdId });
    assert.strictEqual(exactReview.status, 200);
    assert.strictEqual(exactReview.data.proposal_id, createdId);
    assert.strictEqual(exactReview.data.diff.includes('app/src/main/assets/spoonknife/index.html'), true);
    assert.strictEqual(exactReview.data.diff.includes('app/src/main/assets/spoonknife/styles.css'), true);
    assert.strictEqual(exactReview.data.diff.includes('SpoonKnifeDemoActivity.kt'), true);
    assert.strictEqual(exactReview.data.diff.includes('SpoonKnifeDemoScreenSkill.kt'), true);
    assert.strictEqual(exactReview.data.files.length >= 4, true);

    // F. Malformed ID retrieval (hyphens instead of underscores)
    const hyphenatedId = createdId.replace(/_/g, '-');
    const hyphenReview = await post('/api/review-proposal', { proposal_id: hyphenatedId });
    assert.strictEqual(hyphenReview.status, 200);
    assert.strictEqual(hyphenReview.data.proposal_id, createdId, 'Hyphenated lookup must resolve to canonical ID');

    // G. Prefix / timestamp ID retrieval (e.g. truncated suffix like in Gemini voice)
    const timestampPart = createdId.split('_')[1];
    const prefixId = `proposal-${timestampPart}`;
    const prefixReview = await post('/api/review-proposal', { proposal_id: prefixId });
    assert.strictEqual(prefixReview.status, 200);
    assert.strictEqual(prefixReview.data.proposal_id, createdId, 'Prefix/timestamp lookup must resolve to canonical ID');

    // H. 'latest' proposal retrieval
    const latestReview = await post('/api/review-proposal', { proposal_id: 'latest' });
    assert.strictEqual(latestReview.status, 200);
    assert.strictEqual(typeof latestReview.data.proposal_id, 'string');

    // I. Non-existent ID retrieval returning 404 with helpful regeneration suggestion
    const unknownReview = await post('/api/review-proposal', { proposal_id: 'proposal_unknown_nonexistent_999' });
    assert.strictEqual(unknownReview.status, 404);
    assert.strictEqual(unknownReview.data.error.includes('not found or expired'), true);
    assert.strictEqual(typeof unknownReview.data.suggestion, 'string');
    assert.strictEqual(Array.isArray(unknownReview.data.availableProposals), true);

    // J. Stale or mismatched approval token rejection on apply
    const staleApply = await post('/api/apply-proposal', {
        proposal_id: createdId,
        approval_token: 'wrong_stale_attacker_token_12345'
    });
    assert.strictEqual(staleApply.status, 403);
    assert.strictEqual(staleApply.data.error.includes('Invalid or mismatched approval_token'), true);

    // K. Proposal listing via GET /api/proposals (must not expose approval tokens)
    const listRes = await get('/api/proposals');
    assert.strictEqual(listRes.status, 200);
    assert.strictEqual(listRes.data.status, 'SUCCESS');
    assert.strictEqual(listRes.data.count > 0, true);
    for (const item of listRes.data.proposals) {
        assert.strictEqual(item.approvalToken, undefined, 'approvalToken must NOT be leaked in public listing');
        assert.strictEqual(typeof item.proposalId, 'string');
        assert.strictEqual(typeof item.featureName, 'string');
    }

    // L. Natural language & voice-spaced timestamp review
    const spacedTimestampReview = await post('/api/review-proposal', { proposal_id: `proposal ${timestampPart.slice(0, 4)} ${timestampPart.slice(4)}` });
    assert.strictEqual(spacedTimestampReview.status, 200);
    assert.strictEqual(spacedTimestampReview.data.proposal_id, createdId, 'Spaced timestamp digits must resolve to canonical proposal');

    const naturalSpoonKnifeReview = await post('/api/review-proposal', { proposal_id: 'Spoon Knife' });
    assert.strictEqual(naturalSpoonKnifeReview.status, 200);
    assert.strictEqual(naturalSpoonKnifeReview.data.feature_name, 'Spoon-Knife Demo Screen', 'Natural language "Spoon Knife" must resolve to Spoon-Knife proposal');

    const genericReview = await post('/api/review-proposal', { proposal_id: 'the integration proposal' });
    assert.strictEqual(genericReview.status, 200);
    assert.strictEqual(typeof genericReview.data.proposal_id, 'string');

    // M. Ambiguity detection test (multiple proposals matching truncated query)
    const ambPropA = {
        proposalId: 'proposal_888899991111_aaaa',
        featureName: 'AmbiguousFeatureA',
        targetFile: 'app/A.kt',
        proposedContent: '// A',
        diffPreview: '--- A',
        status: 'PENDING_USER_APPROVAL',
        approvalToken: 'token_a'
    };
    const ambPropB = {
        proposalId: 'proposal_888899991111_bbbb',
        featureName: 'AmbiguousFeatureB',
        targetFile: 'app/B.kt',
        proposedContent: '// B',
        diffPreview: '--- B',
        status: 'PENDING_USER_APPROVAL',
        approvalToken: 'token_b'
    };
    pendingProposals.set(ambPropA.proposalId, ambPropA);
    pendingProposals.set(ambPropB.proposalId, ambPropB);

    const ambLookup = findProposal('888899991111');
    assert.strictEqual(ambLookup.ambiguous, true);
    assert.strictEqual(ambLookup.matches.length, 2);

    // Clean up temporary ambiguity test proposals
    pendingProposals.delete(ambPropA.proposalId);
    pendingProposals.delete(ambPropB.proposalId);

    // N. Server restart & durable persistence test
    // Verify that the HTTP server persisted to durable file and that restart reload recovers it
    assert.strictEqual(fs.existsSync(DURABLE_PROPOSALS_FILE), true);
    loadPersistedState();
    assert.strictEqual(pendingProposals.has(createdId), true, 'Persisted proposal must be present in durable state');
    const inMemCountBefore = pendingProposals.size;
    pendingProposals.clear();
    assert.strictEqual(pendingProposals.size, 0);
    loadPersistedState();
    assert.strictEqual(pendingProposals.size, inMemCountBefore);
    assert.strictEqual(pendingProposals.has(createdId), true);
    console.log('   PASSED: Durable persistence, restart recovery, resolution, and security verified.');

    console.log('\n[Companion Tests] ALL 7 TEST SUITES PASSED (100% Success Rate)!');
}

runAsyncTests().catch(err => {
    console.error('Companion async tests failed:', err);
    process.exit(1);
});
