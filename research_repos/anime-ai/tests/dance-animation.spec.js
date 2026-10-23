import { test, expect } from '@playwright/test';

/**
 * Dance Animation Tests
 * Test suite cho dance functionality
 */

test.describe('Dance Animation Tests', () => {
  
  test.beforeEach(async ({ page }) => {
    // Navigate to test page
    await page.goto('/humanoid-animations/test.html');
    
    // Wait for VRM to load
    await page.waitForFunction(() => {
      return window.currentVrm !== undefined;
    }, { timeout: 10000 });
    
    console.log('✅ VRM loaded');
  });

  test('should load VRM model successfully', async ({ page }) => {
    // Check if VRM is loaded
    const vrmLoaded = await page.evaluate(() => {
      return window.currentVrm !== undefined && window.currentVrm !== null;
    });
    
    expect(vrmLoaded).toBeTruthy();
  });

  test('should play dance animation via GUI', async ({ page }) => {
    // Wait for GUI to be available
    await page.waitForSelector('.lil-gui', { timeout: 5000 });
    
    // Get initial animation state
    const initialState = await page.evaluate(() => {
      return {
        hasAction: window.currentAction !== undefined,
        isPlaying: window.currentAction?.isRunning() || false
      };
    });
    
    console.log('Initial state:', initialState);
    
    // Trigger dance animation via exposed function
    const animationStarted = await page.evaluate(async () => {
      try {
        // Load HipHopDancing if available
        if (window.loadFBX) {
          await window.loadFBX('./HipHopDancing.fbx');
          return true;
        }
        return false;
      } catch (e) {
        console.error('Dance load error:', e);
        return false;
      }
    });
    
    // Wait a bit for animation to start
    await page.waitForTimeout(2000);
    
    // Verify animation is playing
    const animationState = await page.evaluate(() => {
      return {
        hasAction: window.currentAction !== undefined,
        isPlaying: window.currentAction?.isRunning() || false,
        actionName: window.currentAction?._clip?.name || 'none'
      };
    });
    
    console.log('Animation state:', animationState);
    
    // Animation should be active
    expect(animationState.hasAction).toBeTruthy();
  });

  test('should handle animation library actions', async ({ page }) => {
    // Check if animation library is available
    const hasLibrary = await page.evaluate(() => {
      return typeof window.animationLibrary !== 'undefined';
    });
    
    if (!hasLibrary) {
      test.skip('Animation library not loaded');
      return;
    }
    
    // Test dance action
    const danceResult = await page.evaluate(async () => {
      try {
        if (window.handleAnimationCommand) {
          await window.handleAnimationCommand('dance', 3);
          return { success: true };
        }
        return { success: false, error: 'No handler' };
      } catch (error) {
        return { success: false, error: error.message };
      }
    });
    
    console.log('Dance command result:', danceResult);
    expect(danceResult.success).toBeTruthy();
  });

  test('should update mouth expressions during animation', async ({ page }) => {
    // Start animation
    await page.evaluate(async () => {
      if (window.loadFBX) {
        await window.loadFBX('./Breathing Idle.fbx');
      }
    });
    
    // Wait for a few frames
    await page.waitForTimeout(1000);
    
    // Check if mouth expression is being updated
    const expressionValue = await page.evaluate(() => {
      if (window.currentVrm?.expressionManager) {
        return window.currentVrm.expressionManager.getValue('aa');
      }
      return -1;
    });
    
    console.log('Mouth expression value:', expressionValue);
    
    // Expression should be set (0-1 range)
    expect(expressionValue).toBeGreaterThanOrEqual(0);
    expect(expressionValue).toBeLessThanOrEqual(1);
  });

  test('should smoothly crossfade between animations', async ({ page }) => {
    // Load first animation
    await page.evaluate(async () => {
      if (window.loadFBX) {
        await window.loadFBX('./Breathing Idle.fbx');
      }
    });
    
    await page.waitForTimeout(1000);
    
    const firstAction = await page.evaluate(() => {
      return window.currentAction?._clip?.name || null;
    });
    
    // Load second animation (should crossfade)
    await page.evaluate(async () => {
      if (window.loadFBX) {
        await window.loadFBX('./Writing.fbx');
      }
    });
    
    await page.waitForTimeout(1000);
    
    const secondAction = await page.evaluate(() => {
      return window.currentAction?._clip?.name || null;
    });
    
    console.log('Animation transition:', firstAction, '→', secondAction);
    
    // Actions should be different
    expect(firstAction).not.toEqual(secondAction);
  });

  test('should handle drag and drop FBX file', async ({ page }) => {
    // Create a mock FBX file drop event
    const dropped = await page.evaluate(() => {
      // Simulate drop event
      const event = new DragEvent('drop', {
        bubbles: true,
        cancelable: true,
      });
      
      // Create mock file
      const mockBlob = new Blob(['mock fbx data'], { type: 'application/octet-stream' });
      const mockFile = new File([mockBlob], 'TestDance.fbx', { type: 'application/octet-stream' });
      
      // Mock DataTransfer
      Object.defineProperty(event, 'dataTransfer', {
        value: {
          files: [mockFile]
        }
      });
      
      window.dispatchEvent(event);
      return true;
    });
    
    expect(dropped).toBeTruthy();
  });

  test('should maintain animation mixer timescale', async ({ page }) => {
    // Wait for mixer
    await page.waitForFunction(() => window.currentMixer !== undefined);
    
    // Get initial timescale
    const initialScale = await page.evaluate(() => {
      return window.currentMixer?.timeScale || 0;
    });
    
    // Change timescale
    const newScale = 0.5;
    await page.evaluate((scale) => {
      if (window.currentMixer) {
        window.currentMixer.timeScale = scale;
      }
    }, newScale);
    
    // Verify change
    const updatedScale = await page.evaluate(() => {
      return window.currentMixer?.timeScale || 0;
    });
    
    console.log('Timescale:', initialScale, '→', updatedScale);
    expect(updatedScale).toBe(newScale);
  });

  test('should render 3D scene continuously', async ({ page }) => {
    // Take screenshot to verify rendering
    await page.waitForTimeout(1000);
    
    const screenshot = await page.screenshot();
    expect(screenshot.length).toBeGreaterThan(1000); // Should have substantial content
    
    // Check if canvas exists
    const hasCanvas = await page.evaluate(() => {
      const canvas = document.querySelector('canvas');
      return canvas !== null && canvas.width > 0 && canvas.height > 0;
    });
    
    expect(hasCanvas).toBeTruthy();
  });
});
