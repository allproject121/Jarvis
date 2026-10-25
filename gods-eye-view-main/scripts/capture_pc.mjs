import puppeteer from 'puppeteer';

const outPath = 'C:/Users/Roshan/.gemini/antigravity/brain/2894b510-0e63-471b-82ff-ebd9508b6d1e/pc_godseye_cctv_rendered.png';

async function main() {
  console.log('Launching browser with hardware acceleration...');
  const browser = await puppeteer.launch({
    headless: true,
    executablePath: 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    args: [
      '--no-sandbox',
      '--disable-setuid-sandbox',
      '--enable-webgl',
      '--ignore-gpu-blocklist',
      '--use-gl=angle',
      '--use-angle=d3d11',
      '--window-size=1920,1080',
    ],
    defaultViewport: { width: 1920, height: 1080 },
  });

  try {
    const page = await browser.newPage();
    console.log('Navigating to http://localhost:4173/?welcome=0...');
    
    await page.goto('http://localhost:4173/?welcome=0', {
      waitUntil: 'domcontentloaded',
      timeout: 60000,
    });

    console.log('Waiting for Cesium viewer to initialize...');
    await page.waitForFunction(
      () => Boolean(window.__godsEyeView?.viewer?.scene),
      { timeout: 60000 }
    );

    console.log('Cesium viewer initialized! Dismissing any modals...');
    await page.keyboard.press('Escape');
    await new Promise((r) => setTimeout(r, 1000));
    await page.keyboard.press('Escape');

    // Trigger CCTV toggle via console if available
    await page.evaluate(() => {
      const gev = window.__godsEyeView;
      if (gev?.toggleCctv) {
        gev.toggleCctv(true);
      } else {
        // Find CCTV button in DOM
        const cctvBtn = Array.from(document.querySelectorAll('button, [role="button"]'))
          .find(el => el.textContent && el.textContent.includes('CCTV'));
        if (cctvBtn) cctvBtn.click();
      }
    });

    console.log('Waiting 6 seconds for CCTV markers and imagery...');
    await new Promise((r) => setTimeout(r, 6000));

    console.log('Taking CCTV screenshot...');
    await page.screenshot({ path: outPath });
    console.log(`Saved screenshot to ${outPath}`);
  } finally {
    await browser.close();
  }
}

main().catch((err) => {
  console.error('Error running PC capture:', err);
  process.exit(1);
});
