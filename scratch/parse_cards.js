const fs = require('fs');
const content = fs.readFileSync('scratch/dump.xml', 'utf8');

const regex = /<node[^>]*resource-id="com\.jarvis\.assistant:id\/cardVaultRoot"[^>]*bounds="([^"]+)"[\s\S]*?(?=<node[^>]*resource-id="com\.jarvis\.assistant:id\/cardVaultRoot"|<\/node><\/node><\/node><node)/g;

let match;
while ((match = regex.exec(content)) !== null) {
  const cardXml = match[0];
  const bounds = match[1];
  const titleMatch = cardXml.match(/resource-id="com\.jarvis\.assistant:id\/tvCardTitle"[^>]*text="([^"]+)"/) || cardXml.match(/text="([^"]+)"[^>]*resource-id="com\.jarvis\.assistant:id\/tvCardTitle"/);
  const activeMatch = cardXml.match(/resource-id="com\.jarvis\.assistant:id\/tvCardActiveBadge"[^>]*text="([^"]+)"/) || cardXml.match(/text="([^"]+)"[^>]*resource-id="com\.jarvis\.assistant:id\/tvCardActiveBadge"/);
  console.log({
    bounds,
    title: titleMatch ? titleMatch[1] : null,
    active: activeMatch ? activeMatch[1] : null
  });
}
