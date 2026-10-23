import { test, expect } from '@playwright/test';

/**
 * AI Livestream Tests
 * Test cho Gemini Live API integration
 */

test.describe('AI Livestream System', () => {
  
  test.beforeEach(async ({ page }) => {
    // Navigate to live page
    await page.goto('/humanoid-animations/live.html');
    
    // Wait for VRM to load
    await page.waitForFunction(() => {
      return window.currentVrm !== undefined;
    }, { timeout: 15000 });
    
    console.log('✅ Live page loaded');
  });

  test('should load livestream UI components', async ({ page }) => {
    // Check for main UI elements
    const infoPanel = await page.locator('#info').isVisible();
    const chatContainer = await page.locator('#chat-container').isVisible();
    const instructions = await page.locator('.instructions').isVisible();
    
    expect(infoPanel).toBeTruthy();
    expect(chatContainer).toBeTruthy();
    expect(instructions).toBeTruthy();
  });

  test('should have connect and disconnect buttons', async ({ page }) => {
    const connectBtn = await page.locator('#btn-connect').isVisible();
    const disconnectBtn = await page.locator('#btn-disconnect').isVisible();
    
    expect(connectBtn).toBeTruthy();
    expect(disconnectBtn).toBeTruthy();
    
    // Disconnect should be disabled initially
    const disconnectDisabled = await page.locator('#btn-disconnect').isDisabled();
    expect(disconnectDisabled).toBeTruthy();
  });

  test('should show offline status initially', async ({ page }) => {
    const statusText = await page.locator('#status-text').textContent();
    expect(statusText).toContain('Offline');
    
    // Status dot should not have 'connected' class
    const hasConnectedClass = await page.locator('#status-dot').evaluate(el => {
      return el.classList.contains('connected');
    });
    expect(hasConnectedClass).toBeFalsy();
  });

  test('should have animation library loaded', async ({ page }) => {
    const hasLibrary = await page.evaluate(() => {
      return typeof window.animationLibrary !== 'undefined';
    });
    
    expect(hasLibrary).toBeTruthy();
    
    // Check for specific dance animations
    const hasDance = await page.evaluate(() => {
      return window.animationLibrary?.dance !== undefined;
    });
    expect(hasDance).toBeTruthy();
  });

  test('should expose animation command handler', async ({ page }) => {
    const hasHandler = await page.evaluate(() => {
      return typeof window.handleAnimationCommand === 'function';
    });
    
    expect(hasHandler).toBeTruthy();
  });

  test('should test animation command without AI', async ({ page }) => {
    // Test dance command directly
    const result = await page.evaluate(async () => {
      try {
        // Call animation handler directly (bypass AI)
        if (window.handleAnimationCommand) {
          await window.handleAnimationCommand('dance', 3, 1.0);
          return { success: true };
        }
        return { success: false, error: 'Handler not found' };
      } catch (error) {
        return { success: false, error: error.message };
      }
    });
    
    console.log('Animation command result:', result);
    expect(result.success).toBeTruthy();
  });

  test('should test all animation categories', async ({ page }) => {
    const categories = ['greet', 'dance', 'excited', 'laugh', 'thinking', 'sad', 'bow', 'heart', 'surprised'];
    
    for (const category of categories) {
      const result = await page.evaluate(async (cat) => {
        try {
          if (window.handleAnimationCommand) {
            await window.handleAnimationCommand(cat, 2, 1.0);
            return { category: cat, success: true };
          }
          return { category: cat, success: false };
        } catch (error) {
          return { category: cat, success: false, error: error.message };
        }
      }, category);
      
      console.log(`Testing ${category}:`, result);
      
      // Wait between animations
      await page.waitForTimeout(500);
    }
  });

  test('should have chat input disabled initially', async ({ page }) => {
    const chatInput = page.locator('#chat-input');
    const isDisabled = await chatInput.isDisabled();
    
    expect(isDisabled).toBeTruthy();
  });

  test('should handle lip-sync controller initialization', async ({ page }) => {
    const hasLipSync = await page.evaluate(() => {
      return window.lipSync !== undefined && window.lipSync !== null;
    });
    
    expect(hasLipSync).toBeTruthy();
  });

  test('should render VRM character in scene', async ({ page }) => {
    // Wait for rendering
    await page.waitForTimeout(1500);
    
    // Take screenshot
    const screenshot = await page.screenshot();
    expect(screenshot.length).toBeGreaterThan(5000);
    
    // Verify VRM scene is in DOM
    const hasScene = await page.evaluate(() => {
      return window.currentVrm?.scene !== undefined;
    });
    expect(hasScene).toBeTruthy();
  });

  test('should have GUI controls visible', async ({ page }) => {
    // Wait for lil-gui
    await page.waitForSelector('.lil-gui', { timeout: 5000 });
    
    const guiVisible = await page.locator('.lil-gui').isVisible();
    expect(guiVisible).toBeTruthy();
  });

  test('should test dance animation via GUI', async ({ page }) => {
    // Wait for GUI
    await page.waitForSelector('.lil-gui');
    
    // Find and click dance button in GUI
    // Note: This requires the GUI to be properly structured
    const danceTestResult = await page.evaluate(async () => {
      try {
        // Directly trigger dance via animation library
        const animData = window.getRandomAnimationFile?.('dance');
        if (animData) {
          return { success: true, file: animData.file };
        }
        return { success: false, error: 'No animation data' };
      } catch (error) {
        return { success: false, error: error.message };
      }
    });
    
    console.log('Dance test result:', danceTestResult);
  });

  test('should support multiple animation files per action', async ({ page }) => {
    const danceFiles = await page.evaluate(() => {
      return window.animationLibrary?.dance?.files || [];
    });
    
    console.log('Dance files available:', danceFiles);
    expect(danceFiles.length).toBeGreaterThan(0);
    
    // Should have multiple dance options
    expect(danceFiles).toContain('HipHopDancing.fbx');
  });

  test('should handle window resize', async ({ page }) => {
    const initialSize = await page.viewportSize();
    
    // Resize window
    await page.setViewportSize({ width: 800, height: 600 });
    await page.waitForTimeout(500);
    
    // Verify canvas updated
    const canvasSize = await page.evaluate(() => {
      const canvas = document.querySelector('canvas');
      return {
        width: canvas?.width || 0,
        height: canvas?.height || 0
      };
    });
    
    console.log('Canvas size:', canvasSize);
    expect(canvasSize.width).toBeGreaterThan(0);
    expect(canvasSize.height).toBeGreaterThan(0);
  });

  test('should expose global functions for testing', async ({ page }) => {
    const globalFunctions = await page.evaluate(() => {
      return {
        connectAI: typeof window.connectAI === 'function',
        disconnectAI: typeof window.disconnectAI === 'function',
        sendTextToAI: typeof window.sendTextToAI === 'function',
        handleAnimationCommand: typeof window.handleAnimationCommand === 'function',
        handleAudioOutput: typeof window.handleAudioOutput === 'function'
      };
    });
    
    console.log('Global functions:', globalFunctions);
    
    expect(globalFunctions.connectAI).toBeTruthy();
    expect(globalFunctions.disconnectAI).toBeTruthy();
    expect(globalFunctions.sendTextToAI).toBeTruthy();
    expect(globalFunctions.handleAnimationCommand).toBeTruthy();
  });
});

test.describe('Animation Performance', () => {
  test('should maintain acceptable FPS', async ({ page }) => {
    await page.goto('/humanoid-animations/live.html');
    
    // Wait for scene to load
    await page.waitForFunction(() => window.currentVrm !== undefined);
    await page.waitForTimeout(2000);
    
    // Measure FPS over 3 seconds
    const fps = await page.evaluate(async () => {
      return new Promise((resolve) => {
        let frames = 0;
        const startTime = performance.now();
        
        function countFrame() {
          frames++;
          const elapsed = performance.now() - startTime;
          
          if (elapsed >= 3000) {
            const avgFps = (frames / elapsed) * 1000;
            resolve(avgFps);
          } else {
            requestAnimationFrame(countFrame);
          }
        }
        
        requestAnimationFrame(countFrame);
      });
    });
    
    console.log('Average FPS:', fps);
    
    // Should maintain at least 30 FPS
    expect(fps).toBeGreaterThan(30);
  });
});
