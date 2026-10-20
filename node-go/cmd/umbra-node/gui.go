//go:build desktop

package main

import (
	"app.umbra/node/frontend"
	"github.com/wailsapp/wails/v2"
	"github.com/wailsapp/wails/v2/pkg/options"
	"github.com/wailsapp/wails/v2/pkg/options/assetserver"
)

func runGUI(w *Wizard) error {
	return wails.Run(&options.App{Title: "Umbra Node 設定", Width: 960, Height: 640, MinWidth: 800, MinHeight: 600, AssetServer: &assetserver.Options{Assets: frontend.Assets}, BackgroundColour: &options.RGBA{R: 227, G: 234, B: 223, A: 255}, Bind: []interface{}{w}})
}
