package main

import (
	"embed"
	"os"

	"github.com/wailsapp/wails/v2"
	"github.com/wailsapp/wails/v2/pkg/options"
	"github.com/wailsapp/wails/v2/pkg/options/assetserver"
)

// appVersion is injected at build time via -ldflags "-X main.appVersion=...".
// It stays "dev" for local/non-release builds; CheckForUpdate treats "dev"
// as "do not check for updates".
var appVersion = "dev"

//go:embed all:frontend/dist
var assets embed.FS

func main() {
	// Create an instance of the app structure
	app := NewApp()
	app.SetStartupArgs(os.Args[1:])

	// Create application with options. The single-instance lock routes later
	// launches (e.g. another double-clicked .md file) into this window via
	// mdview:open-path instead of spawning a second process window.
	err := wails.Run(&options.App{
		Title:  "simplify2md — Markdown",
		Width:  1400,
		Height: 900,
		AssetServer: &assetserver.Options{
			Assets: assets,
		},
		BackgroundColour: &options.RGBA{R: 255, G: 255, B: 255, A: 1},
		OnStartup:        app.startup,
		OnBeforeClose:    app.beforeClose,
		Bind: []interface{}{
			app,
		},
		SingleInstanceLock: &options.SingleInstanceLock{
			UniqueId:               "simplify2md-6f3a9c2e-single-instance",
			OnSecondInstanceLaunch: app.onSecondInstanceLaunch,
		},
	})

	if err != nil {
		println("Error:", err.Error())
	}
}
