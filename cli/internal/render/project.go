package render

import (
	"io"
	"strconv"
)

type projectResponse struct {
	Name       string `json:"name"`
	Path       string `json:"path"`
	Status     string `json:"status"`
	ClassCount int    `json:"classCount"`
	ScannedAt  string `json:"scannedAt"`
}

// Project renders `project` (project info) as a key/value block.
func Project(w io.Writer, v any) error {
	resp, err := decode[projectResponse](v)
	if err != nil {
		return err
	}
	return KVBlock(w, [][2]string{
		{"Name:", dash(resp.Name)},
		{"Path:", dash(resp.Path)},
		{"Status:", dash(resp.Status)},
		{"Classes:", strconv.Itoa(resp.ClassCount)},
		{"Scanned At:", dash(resp.ScannedAt)},
	})
}
