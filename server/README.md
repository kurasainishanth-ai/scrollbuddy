# Scroll Sentry Backend

This is the production-ready backend for Scroll Sentry.

## Deployment to Render (Free)

1. **Push this code to GitHub.**
2. **Create a new Web Service on Render.**
3. **Connect your GitHub repository.**
4. **Environment Variables:**
   - `PUBLIC_URL`: Set this to your Render service URL (e.g., `https://scroll-sentry-backend.onrender.com`). This is required for friend approval links to work over the internet.
   - `NODE_ENV`: `production`

## Deployment to Railway

1. **Push to GitHub.**
2. **Create a new project on Railway.**
3. **Add a new service from your GitHub repo.**
4. **Variables:**
   - `PUBLIC_URL`: Railway provides an automatic URL, but you should set this manually to the provided domain to ensure links are generated correctly.

## Android App Setup

Update your `gradle.properties` (or `.env` if using secrets plugin) with:
```properties
SERVER_URL=https://your-app-name.onrender.com
```
Then rebuild and deploy the app to your phone.
