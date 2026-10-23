#!/bin/bash

# Virtual Idol Setup Script
echo "🎤 Virtual Idol Livestream - Quick Setup"
echo "========================================"
echo ""

# Check if API key is set
if grep -q "YOUR_API_KEY_HERE" humanoid-animations/main.js; then
    echo "⚠️  WARNING: API Key chưa được cấu hình!"
    echo ""
    echo "Hướng dẫn setup:"
    echo "1. Truy cập: https://aistudio.google.com/apikey"
    echo "2. Tạo API key mới"
    echo "3. Mở humanoid-animations/main.js"
    echo "4. Thay 'YOUR_API_KEY_HERE' bằng API key của bạn"
    echo ""
    read -p "Nhấn Enter sau khi đã setup API key..."
fi

# Check if node_modules exists
if [ ! -d "node_modules" ]; then
    echo "📦 Installing dependencies..."
    npm install
    echo "✅ Dependencies installed!"
    echo ""
fi

# Check for animation files
echo "🎭 Checking animation files..."
if [ ! -f "humanoid-animations/Breathing Idle.fbx" ]; then
    echo "⚠️  Breathing Idle.fbx not found"
    echo "   Download from: https://www.mixamo.com"
    echo "   Search: 'Breathing Idle'"
fi

if [ ! -f "humanoid-animations/Wave.fbx" ]; then
    echo "⚠️  Wave.fbx not found (optional)"
fi

echo ""
echo "✅ Setup complete!"
echo ""
echo "🚀 Starting dev server..."
echo "   Open: http://localhost:5173/humanoid-animations/live.html"
echo ""
echo "Press Ctrl+C to stop"
echo ""

npm run dev
