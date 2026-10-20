package frontend

import "embed"

//go:embed index.html wizard.css wizard.js assets
var Assets embed.FS
