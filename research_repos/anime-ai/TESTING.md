# 🧪 Testing Guide

## Overview

Project này sử dụng **Playwright** cho end-to-end testing của VRM character system và AI livestream features.

## Quick Start

```bash
# Install dependencies
npm install

# Run all tests
npm test

# Run with UI (recommended for debugging)
npm run test:ui

# Run in headed mode (see browser)
npm run test:headed
```

## Test Suites

### 1. Dance Animation Tests (`tests/dance-animation.spec.js`)

Tests cơ bản cho animation system:

- ✅ VRM model loading
- ✅ Animation playback
- ✅ Mouth expressions
- ✅ Animation crossfading
- ✅ Drag & drop FBX files
- ✅ Mixer timescale control

**Run only dance tests:**
```bash
npx playwright test dance-animation
```

### 2. AI Livestream Tests (`tests/ai-livestream.spec.js`)

Tests cho AI integration và UI:

- ✅ UI component rendering
- ✅ Animation library integration
- ✅ Command handlers
- ✅ Global function exports
- ✅ Chat interface
- ✅ Performance metrics

**Run only livestream tests:**
```bash
npx playwright test ai-livestream
```

## Test Structure

```
tests/
├── dance-animation.spec.js    # Animation system tests
└── ai-livestream.spec.js      # AI & UI integration tests

playwright.config.js           # Playwright configuration
```

## Writing Tests

### Example Test

```javascript
import { test, expect } from '@playwright/test';

test('should play dance animation', async ({ page }) => {
  // Navigate
  await page.goto('/humanoid-animations/live.html');
  
  // Wait for VRM
  await page.waitForFunction(() => window.currentVrm !== undefined);
  
  // Trigger animation
  await page.evaluate(() => {
    window.handleAnimationCommand('dance', 5);
  });
  
  // Verify
  const hasAction = await page.evaluate(() => {
    return window.currentAction !== undefined;
  });
  expect(hasAction).toBeTruthy();
});
```

## Exposed Global Functions

For testing, these functions are exposed on `window`:

```javascript
// VRM & Animation State
window.currentVrm          // Current VRM instance
window.currentMixer        // Three.js AnimationMixer
window.currentAction       // Current AnimationAction

// AI Controllers
window.geminiLive          // GeminiLiveController instance
window.lipSync             // LipSyncController instance

// Animation Library
window.animationLibrary    // Animation definitions
window.getRandomAnimationFile(action) // Get random animation

// Functions
window.loadFBX(url)                          // Load FBX animation
window.handleAnimationCommand(action, dur)   // Trigger animation
window.handleAudioOutput(audioData)          // Process audio
window.connectAI()                           // Connect to Gemini
window.disconnectAI()                        // Disconnect
window.sendTextToAI(text)                    // Send text message
```

## Common Test Patterns

### Wait for VRM to Load

```javascript
await page.waitForFunction(() => {
  return window.currentVrm !== undefined;
}, { timeout: 10000 });
```

### Trigger Animation

```javascript
await page.evaluate(async () => {
  await window.handleAnimationCommand('dance', 3);
});
```

### Check Expression Values

```javascript
const mouthValue = await page.evaluate(() => {
  return window.currentVrm?.expressionManager?.getValue('aa');
});
expect(mouthValue).toBeGreaterThanOrEqual(0);
expect(mouthValue).toBeLessThanOrEqual(1);
```

### Take Screenshot

```javascript
await page.screenshot({ 
  path: 'screenshots/dance-animation.png' 
});
```

## Debugging Tests

### Interactive UI Mode

```bash
npm run test:ui
```

Features:
- ✅ See all tests
- ✅ Run individual tests
- ✅ Watch mode
- ✅ Time travel debugging

### Headed Mode

```bash
npm run test:headed
```

See the actual browser during test execution.

### Debug Specific Test

```bash
npx playwright test --debug -g "should play dance"
```

### VS Code Extension

Install **Playwright Test for VSCode**:
- Run/debug tests from editor
- Set breakpoints
- View test results inline

## CI/CD Integration

### GitHub Actions Example

```yaml
name: Tests

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-node@v3
        with:
          node-version: '18'
      
      - name: Install dependencies
        run: npm ci
      
      - name: Install Playwright
        run: npx playwright install --with-deps chromium
      
      - name: Run tests
        run: npm test
      
      - name: Upload test results
        if: always()
        uses: actions/upload-artifact@v3
        with:
          name: playwright-report
          path: playwright-report/
```

## Test Configuration

### `playwright.config.js`

```javascript
export default defineConfig({
  testDir: './tests',
  
  // Run tests in parallel
  fullyParallel: true,
  
  // Retry failed tests
  retries: process.env.CI ? 2 : 0,
  
  // Browser
  use: {
    baseURL: 'http://localhost:5173',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  
  // Start dev server
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
  },
});
```

## Troubleshooting

### Test Timeouts

Increase timeout for slow operations:

```javascript
test('slow test', async ({ page }) => {
  test.setTimeout(60000); // 60 seconds
  // ... test code
});
```

### Missing FBX Files

Some tests require actual animation files:

```bash
# Download from Mixamo: https://www.mixamo.com
# Place in humanoid-animations/:
- HipHopDancing.fbx
- Wave.fbx
- Jump.fbx
```

Or skip tests:

```javascript
test('test requiring FBX', async ({ page }) => {
  const hasFBX = await checkFBXExists();
  if (!hasFBX) {
    test.skip('FBX file not available');
    return;
  }
  // ... test code
});
```

### Flaky Tests

Add wait times for animations:

```javascript
await page.waitForTimeout(1000); // Wait 1 second
```

Or wait for specific conditions:

```javascript
await page.waitForFunction(() => {
  return window.currentAction?.isRunning();
});
```

## Test Reports

### HTML Report

Generated automatically after tests:

```bash
npx playwright show-report
```

### JSON Report

```bash
npx playwright test --reporter=json
```

### Custom Reporter

```javascript
// playwright.config.js
reporter: [
  ['html'],
  ['json', { outputFile: 'test-results.json' }],
  ['list']
]
```

## Performance Testing

### Measure FPS

```javascript
const fps = await page.evaluate(async () => {
  return new Promise((resolve) => {
    let frames = 0;
    const start = performance.now();
    
    function count() {
      frames++;
      if (performance.now() - start < 3000) {
        requestAnimationFrame(count);
      } else {
        resolve((frames / 3) * 1000);
      }
    }
    requestAnimationFrame(count);
  });
});

console.log('Average FPS:', fps);
```

### Measure Load Time

```javascript
const timing = await page.evaluate(() => {
  return performance.timing;
});

const loadTime = timing.loadEventEnd - timing.navigationStart;
console.log('Page load:', loadTime, 'ms');
```

## Best Practices

1. **Use data-testid attributes** for stable selectors:
```html
<button data-testid="connect-button">Connect</button>
```

```javascript
await page.locator('[data-testid="connect-button"]').click();
```

2. **Avoid hard waits**, use smart waits:
```javascript
// ❌ Bad
await page.waitForTimeout(5000);

// ✅ Good
await page.waitForFunction(() => window.currentVrm !== undefined);
```

3. **Test user flows, not implementation**:
```javascript
// ❌ Bad - testing internal state
expect(window.internalState).toBe('ready');

// ✅ Good - testing user-visible outcome
expect(await page.locator('#status').textContent()).toBe('Connected');
```

4. **Clean up between tests**:
```javascript
test.afterEach(async ({ page }) => {
  // Disconnect AI
  await page.evaluate(() => window.disconnectAI?.());
});
```

## Resources

- [Playwright Docs](https://playwright.dev)
- [Best Practices](https://playwright.dev/docs/best-practices)
- [API Reference](https://playwright.dev/docs/api/class-playwright)
- [Project README](README.md)
- [Livestream Guide](LIVESTREAM_GUIDE.md)

---

Happy Testing! 🧪✨
