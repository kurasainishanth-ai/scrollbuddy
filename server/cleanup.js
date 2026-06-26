import admin from 'firebase-admin';
import dotenv from 'dotenv';
import { fileURLToPath } from 'url';
import { dirname, resolve } from 'path';

import { readFileSync, existsSync } from 'fs';

// Load .env if present
const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
dotenv.config({ path: resolve(__dirname, '.env') });

let serviceAccount;

try {
  if (process.env.FIREBASE_SERVICE_ACCOUNT_JSON) {
    serviceAccount = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT_JSON);
    console.log("Loaded credentials from environment variable.");
  } else {
    const localKeyPath = resolve(__dirname, 'firebase-service-account.json');
    if (existsSync(localKeyPath)) {
      serviceAccount = JSON.parse(readFileSync(localKeyPath, 'utf8'));
      console.log("Loaded credentials from local firebase-service-account.json file.");
    } else {
      console.error("ERROR: No credentials found. Please set FIREBASE_SERVICE_ACCOUNT_JSON or create firebase-service-account.json");
      process.exit(1);
    }
  }

  admin.initializeApp({
    credential: admin.credential.cert(serviceAccount)
  });
} catch (e) {
  console.error("ERROR parsing Firebase credentials:", e.message);
  process.exit(1);
}

const db = admin.firestore();

async function cleanup() {
  console.log("Starting audit_log cleanup...");
  let updatedCount = 0;
  
  try {
    const snapshot = await db.collection("audit_log").where("status", "==", "PENDING").get();
    console.log(`Found ${snapshot.size} total PENDING documents.`);
    
    // Firestore batches support up to 500 operations
    const batches = [];
    let currentBatch = db.batch();
    let currentBatchSize = 0;
    
    snapshot.forEach(doc => {
      const data = doc.data();
      if (data.type !== "PROTECTION_LOST" && data.type !== "PROTECTION_EVENT") {
        currentBatch.update(doc.ref, { status: "LOG" });
        updatedCount++;
        currentBatchSize++;
        
        if (currentBatchSize === 500) {
          batches.push(currentBatch.commit());
          currentBatch = db.batch();
          currentBatchSize = 0;
        }
      }
    });
    
    if (currentBatchSize > 0) {
      batches.push(currentBatch.commit());
    }
    
    if (updatedCount > 0) {
      await Promise.all(batches);
      console.log(`Successfully updated ${updatedCount} leaked documents to status: LOG.`);
    } else {
      console.log("No leaked documents found. Database is clean.");
    }
  } catch (error) {
    console.error("Error during cleanup:", error);
  }
}

cleanup();
