package libtailscale

import (
	"encoding/json"
	"fmt"
	"log"
	"os"
	"strconv"
	"strings"
	"time"

	"tailscale.com/envknob"
	"tailscale.com/ipn"
)

const (
	alwaysUseRelayEnabledStateKey = ipn.StateKey("_always_use_relay_enabled")

	sendFilesToPeerCmd = "send_files_to_peer"
)

func isClientDependantCmd(cmd string) bool {
	switch cmd {
	case "log", "get_env_knob":
		return false
	default:
		return true
	}
}

var (
	app   *App
	store *stateStore
)

func setupAppCommandHandler(a *App) {
	app = a
	store = a.store
}

func SendCommand(cmd, args string) string {
	log.Printf("Received cmd: %v args: %v", cmd, args)
	if app == nil && isClientDependantCmd(cmd) {
		return "App not initialized"
	}
	var client *Client
	if app != nil {
		client = NewClient(app)
	}
	switch cmd {
	case "start":
		err := client.Start(args)
		if err != nil {
			return fmt.Sprintf("Error starting: %v", err)
		}
		return "Success"
	case "start_login_interactive":
		log.Printf("calling start login interactive local client")
		err := client.StartLoginInteractive()
		if err != nil {
			log.Printf("start login interface failed: %v", err)
			return fmt.Sprintf("Error starting login interactive: %v", err)
		}
		log.Printf("start login interactive success")
		return "Success"
	case "edit_prefs":
		err := client.EditPrefs(args)
		if err != nil {
			return fmt.Sprintf("Error editing prefs: %v", err)
		}
		return "Success"
	case "profiles":
		result := []ipn.LoginProfile{}
		err := client.Profiles(&result)
		if err != nil {
			return fmt.Sprintf("Error getting profiles: %v", err)
		}
		v, err := json.Marshal(result)
		if err != nil {
			return fmt.Sprintf("Error encoding profiles: %v", err)
		}
		return string(v)
	case "current_profile":
		result := ipn.LoginProfile{}
		err := client.CurrentProfile(&result)
		if err != nil {
			return fmt.Sprintf("Error getting profiles: %v", err)
		}
		v, err := json.Marshal(result)
		if err != nil {
			return fmt.Sprintf("Error encoding profiles: %v", err)
		}
		return string(v)

	case "add_profile":
		err := client.AddProfile()
		if err != nil {
			return fmt.Sprintf("Error add profile: %v", err)
		}
		return "Success"
	case "switch_profile":
		id := ipn.ProfileID(args)
		err := client.SwitchProfile(id)
		if err != nil {
			return fmt.Sprintf("Error switch profile: %v", err)
		}
		return "Success"
	case "ping":
		result, err := client.Ping(args)
		if err != nil {
			return fmt.Sprintf("Error pinging: %v", err)
		}
		return result
	case "status":
		result, err := client.Status()
		if err != nil {
			return fmt.Sprintf("Error getting status: %v", err)
		}
		return result
	case "logout":
		err := client.Logout()
		if err != nil {
			return fmt.Sprintf("Error logging out: %v", err)
		}
		return "Success"
	case "set_env_knobs":
		if args == "" {
			return "Error: no arguments provided"
		}
		kvs, err := parseKeyValue(args)
		if err != nil {
			return fmt.Sprintf("Error parsing arguments: %v", err)
		}
		log.Printf("Set env knob: %v", kvs)
		for k, v := range kvs {
			envknob.Setenv(k, v)
		}
		// Some env knobs need follow up actions
		if v, ok := kvs["TS_DEBUG_ALWAYS_USE_DERP"]; ok {
			if err := onEnvknobSetAlwaysUseRelay(v, client); err != nil {
				return fmt.Sprintf("Error setting TS_DEBUG_ALWAYS_USE_DERP: %v", err)
			}
			log.Printf("TS_DEBUG_ALWAYS_USE_DERP set to %v", v)
		}
		return "Success"
	case "get_env_knob":
		if args == "" {
			return "Error: no arguments provided"
		}
		log.Printf("Get env knob: %v", args)
		return os.Getenv(args)
	case "log":
		log.Println(args)
		return "Success"
	case sendFilesToPeerCmd:
		result := ""
		sendArgs := &SendFilesToPeerArgs{}
		if err := json.Unmarshal([]byte(args), sendArgs); err != nil {
			return fmt.Sprintf("Error unmarshaling args: %v", err)
		}
		if err := client.PutTaildropFiles(sendArgs.PeerID, sendArgs.Files, &result); err != nil {
			return fmt.Sprintf("Error sending files to peer: %v", err)
		}
		return "Success: " + result
	default:
		return fmt.Sprintf("Unknown command: %v", cmd)
	}
}

func onEnvknobSetAlwaysUseRelay(setting string, client *Client) error {
	on, err := strconv.ParseBool(setting)
	if err != nil {
		return fmt.Errorf("failed to parse setting '%q': %w", setting, err)
	}
	if err := store.WriteBool(string(alwaysUseRelayEnabledStateKey), on); err != nil {
		return fmt.Errorf("failed to store state: %w", err)
	}
	if client != nil {
		log.Printf("Rebinding for alwaysUserRelay(%v)", on)
		if err := client.DebugRebind(); err != nil {
			return fmt.Errorf("failed to rebind for alwaysUserRelay(%v): %w", on, err)
		}
		log.Printf("Rebinding DONE. Re-stunning for alwaysUserRelay(%v)", on)
		if err := client.DebugReStun(); err != nil {
			return fmt.Errorf("failed to re-stun for alwaysUserRelay(%v): %w", on, err)
		}
		log.Printf("Re-stunning DONE for alwaysUserRelay(%v)", on)
	}
	return nil
}

func getCmdTimeout(cmd string) time.Duration {
	if cmd == sendFilesToPeerCmd {
		return 24 * time.Hour
	}
	// Default timeout for commands
	return 5 * time.Second
}

type SendFilesToPeerArgs struct {
	PeerID string         `json:"peer_id"`
	Files  []OutgoingFile `json:"files"`
}

// Helper function to parse key-value string into map
func parseKeyValue(s string) (map[string]string, error) {
	result := make(map[string]string)

	// Handle empty string
	if s == "" {
		return result, nil
	}

	// Try JSON first
	if err := json.Unmarshal([]byte(s), &result); err == nil {
		return result, nil
	}

	// Fallback to key=value format
	pairs := strings.Split(s, ",")
	for _, pair := range pairs {
		parts := strings.SplitN(pair, "=", 2)
		if len(parts) != 2 {
			return nil, fmt.Errorf("invalid key-value pair: %s", pair)
		}
		key := strings.TrimSpace(parts[0])
		value := strings.TrimSpace(parts[1])
		result[key] = value
	}

	return result, nil
}
