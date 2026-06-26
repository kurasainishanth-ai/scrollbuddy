const admin = require('firebase-admin');
const serviceAccount = require('./firebase-service-account.json');
admin.initializeApp({ credential: admin.credential.cert(serviceAccount) });
const db = admin.firestore();
async function run() {
  try {
    const res = await db.collection('audit_log')
      .where('status', '==', 'PENDING')
      .where('friends', 'array-contains', 'test_user')
      .get();
    console.log('SUCCESS, no composite index needed or index already exists.');
  } catch(e) {
    console.error('ERROR:', e.message);
  }
}
run();
