const express = require('express');
const path = require('path');
const fs = require('fs');

const app = express();
const PORT = process.env.PORT || 3001;

// Version endpoint for Android app
app.get('/api/version', (req, res) => {
    res.json({
        android: {
            versionCode: 2,
            versionName: "1.0.1",
            downloadUrl: `https://${req.get('host')}/download/app-release.apk`,
            changelog: "Permanent device code & remote mouse control fixes with modern progress UI."
        }
    });
});

// Serve release APK file
app.use('/download', express.static(path.join(__dirname, 'releases')));

// Root status endpoint
app.get('/', (req, res) => {
    res.send(`
        <div style="font-family: sans-serif; text-align: center; padding: 50px; background: #0f172a; color: white;">
            <h2>🚀 StreamLink Android Update Server is Live</h2>
            <p>Serving direct in-app updates for StreamLink Android app.</p>
        </div>
    `);
});

app.listen(PORT, () => {
    console.log(`[+] Android Update Server running on port ${PORT}`);
});
