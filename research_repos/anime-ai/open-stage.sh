#!/bin/bash

echo "🎭 Opening Virtual Idol Stage..."
echo ""

# Check if server is running
if ! curl -s http://localhost:5173 > /dev/null 2>&1; then
    echo "⚠️  Dev server not running!"
    echo "Starting server..."
    npm run dev &
    sleep 3
fi

echo "📖 Available pages:"
echo ""
echo "1. 🎪 Stage Test (Interactive Controls)"
echo "   http://localhost:5173/humanoid-animations/stage-test.html"
echo ""
echo "2. 🎤 AI Livestream (Full Experience)"  
echo "   http://localhost:5173/humanoid-animations/live.html"
echo ""
echo "3. 🧪 Simple Test (Original)"
echo "   http://localhost:5173/humanoid-animations/test.html"
echo ""

# Open browser (Linux/macOS compatible)
if command -v xdg-open > /dev/null; then
    xdg-open "http://localhost:5173/humanoid-animations/stage-test.html"
elif command -v open > /dev/null; then
    open "http://localhost:5173/humanoid-animations/stage-test.html"
else
    echo "💡 Please open http://localhost:5173/humanoid-animations/stage-test.html"
fi
