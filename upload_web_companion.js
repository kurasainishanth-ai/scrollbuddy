const fs = require('fs');
const https = require('https');
const http = require('http');

const fileContent = fs.readFileSync('ScrollSentry_Web_Companion.html');

function uploadToEnvsSh() {
  return new Promise((resolve, reject) => {
    const boundary = '----WebKitFormBoundaryEnvsSh' + Math.random().toString(36).substring(2);
    const body = 
      `--${boundary}\r\n` +
      `Content-Disposition: form-data; name="file"; filename="ScrollSentry_Web_Companion.html"\r\n` +
      `Content-Type: text/html\r\n\r\n` +
      fileContent.toString() + `\r\n` +
      `--${boundary}--\r\n`;

    const options = {
      hostname: 'envs.sh',
      path: '/',
      method: 'POST',
      headers: {
        'Content-Type': `multipart/form-data; boundary=${boundary}`,
        'Content-Length': Buffer.byteLength(body)
      }
    };

    const req = https.request(options, (res) => {
      let data = '';
      res.on('data', chunk => { data += chunk; });
      res.on('end', () => {
        const url = data.trim();
        if (url.startsWith('http')) {
          resolve(url);
        } else {
          reject(new Error('Invalid response from envs.sh: ' + data));
        }
      });
    });
    req.on('error', reject);
    req.write(body);
    req.end();
  });
}

function uploadToX0At() {
  return new Promise((resolve, reject) => {
    const boundary = '----WebKitFormBoundaryX0At' + Math.random().toString(36).substring(2);
    const body = 
      `--${boundary}\r\n` +
      `Content-Disposition: form-data; name="file"; filename="ScrollSentry_Web_Companion.html"\r\n` +
      `Content-Type: text/html\r\n\r\n` +
      fileContent.toString() + `\r\n` +
      `--${boundary}--\r\n`;

    const options = {
      hostname: 'x0.at',
      path: '/',
      method: 'POST',
      headers: {
        'Content-Type': `multipart/form-data; boundary=${boundary}`,
        'Content-Length': Buffer.byteLength(body)
      }
    };

    const req = https.request(options, (res) => {
      let data = '';
      res.on('data', chunk => { data += chunk; });
      res.on('end', () => {
        const url = data.trim();
        if (url.startsWith('http')) {
          resolve(url);
        } else {
          reject(new Error('Invalid response from x0.at: ' + data));
        }
      });
    });
    req.on('error', reject);
    req.write(body);
    req.end();
  });
}

async function main() {
  console.log('Attempting to upload ScrollSentry_Web_Companion.html...');
  
  try {
    const url = await uploadToEnvsSh();
    console.log('UPLOAD SUCCESS (envs.sh):', url);
    fs.writeFileSync('app/web_companion_url.txt', url);
    process.exit(0);
  } catch (e) {
    console.warn('envs.sh failed, trying x0.at...', e.message);
  }

  try {
    const url = await uploadToX0At();
    console.log('UPLOAD SUCCESS (x0.at):', url);
    fs.writeFileSync('app/web_companion_url.txt', url);
    process.exit(0);
  } catch (e) {
    console.error('All upload services failed!', e.message);
    // Write fallback URL using public static renderer with raw repo, 
    // or just a custom placeholder that we will inject in layout.
    fs.writeFileSync('app/web_companion_url.txt', 'https://htmlpreview.github.io/?https://gist.githubusercontent.com/anonymous/raw/ScrollSentry_Web_Companion.html');
    process.exit(1);
  }
}

main();
