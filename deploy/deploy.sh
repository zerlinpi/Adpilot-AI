#!/bin/bash
# AdPilot AI - Deployment Script
# Frontend: https://your-domain.example      (Nginx static SPA)
# Backend:  https://api.your-domain.example  (Java, 127.0.0.1:8090)
# Database: MySQL 8.0 (schema imported manually from backend-java/db/schema.sql)

set -e

echo "=== AdPilot AI Deployment ==="
echo ""

# Configuration
FRONTEND_URL="https://your-domain.example"
BACKEND_URL="https://api.your-domain.example"
DEPLOY_DIR="/opt/adpilot"
DB_NAME="adpilot"
DB_USER="aws"
DB_PASSWORD="aws"

# Step 1: Build frontend
echo "Step 1: Building frontend..."
cd frontend
pnpm install
pnpm build
cd ..
echo "鉁?Frontend built"

# Step 2: Build backend
echo "Step 2: Building backend..."
cd backend-java
./mvnw clean package -DskipTests
cd ..
echo "鉁?Backend built"

# Step 3: Create deployment directory
echo "Step 3: Preparing deployment directory..."
mkdir -p $DEPLOY_DIR/frontend
mkdir -p $DEPLOY_DIR/backend
mkdir -p $DEPLOY_DIR/logs
echo "鉁?Directory ready"

# Step 4: Copy files
echo "Step 4: Copying files..."
cp -r frontend/dist/* $DEPLOY_DIR/frontend/
cp backend-java/target/adpilot-*.jar $DEPLOY_DIR/backend/adpilot.jar
cp deploy/nginx.conf /etc/nginx/sites-available/adpilot.conf
echo "鉁?Files copied"

# Step 5: Create the MySQL database and import the schema.
# The DB is decoupled from the app (no Flyway/auto-DDL on startup); the schema
# and seed data live in a single file: backend-java/db/schema.sql.
echo "Step 5: Creating MySQL database and importing schema..."
mysql -u $DB_USER -p$DB_PASSWORD -e "CREATE DATABASE IF NOT EXISTS $DB_NAME CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql -u $DB_USER -p$DB_PASSWORD $DB_NAME < backend-java/db/schema.sql
echo "鉁?Database created and schema imported"

# Step 6: Configure Nginx
echo "Step 6: Configuring Nginx..."
ln -sf /etc/nginx/sites-available/adpilot.conf /etc/nginx/sites-enabled/
nginx -t && systemctl reload nginx
echo "鉁?Nginx configured"

# Step 7: Start backend
echo "Step 7: Starting backend..."
cd $DEPLOY_DIR/backend
nohup java -jar adpilot.jar --spring.profiles.active=prod > ../logs/backend.log 2>&1 &
echo "鉁?Backend started on port 8090"

echo ""
echo "=== Deployment Complete ==="
echo "Frontend: $FRONTEND_URL"
echo "Backend:  $BACKEND_URL"
echo "Database: MySQL @ localhost:3306/$DB_NAME"
echo "Default login: admin@adpilot.local / Adpilot@123456"
echo ""
echo "鈿狅笍 Remember to:"
echo "1. Change default passwords"
echo "2. Configure SSL certificates for both subdomains (e.g. Let's Encrypt)"
echo "3. Set production environment variables (JWT_SECRET, DB_PASSWORD, ENCRYPTION_KEY,"
echo "   ADPILOT_AMAZON_ADS_CLIENT_ID/SECRET/REDIRECT_URI, CORS_ORIGINS)"
echo "4. Install MySQL 8.0 if not already installed"
