//go:build !desktop

package main

import "errors"

func runGUI(w *Wizard) error {
	return errors.New("this is the headless binary; use umbra-node setup --tui in a terminal, or download the desktop edition for the graphical wizard")
}
