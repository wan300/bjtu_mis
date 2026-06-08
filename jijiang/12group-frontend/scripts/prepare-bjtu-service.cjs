const fs = require("fs");
const path = require("path");

const frontendRoot = path.resolve(__dirname, "..");
const repoRoot = path.resolve(frontendRoot, "..");
const sourceDist = path.join(frontendRoot, "dist", "build", "h5");
const targetDist = path.join(repoRoot, "dist");

if (!fs.existsSync(sourceDist)) {
  throw new Error(`Missing H5 build output: ${sourceDist}`);
}

fs.rmSync(targetDist, { recursive: true, force: true });
fs.mkdirSync(targetDist, { recursive: true });
fs.cpSync(sourceDist, targetDist, { recursive: true });

const requiredFiles = [
  path.join(repoRoot, "bjtu-service.json"),
  path.join(targetDist, "index.html"),
  path.join(targetDist, "static", "logo.png")
];

for (const file of requiredFiles) {
  if (!fs.existsSync(file)) {
    throw new Error(`BJTU service package is incomplete: ${file}`);
  }
}

console.log(`BJTU service dist prepared at ${targetDist}`);
