import math
import random
import numpy as np
import matplotlib.pyplot as plt
from collections import deque

# --- PHYSICAL MAPPING: 1 Unit = 1 Meter, 1 Step = 1 Second ---
WORLD_SIZE = 3000      
COMM_RANGE = 50        
RUBBLE_RANGE = 5       
WALK_SPEED = 1.5       
MAX_SIM_TIME = 14400   
NUM_RUNS_PER_DENSITY = 30

RELAY_COUNTS_TO_TEST = [50, 150, 300, 500, 750, 1000]
NUM_RUBBLE_ZONES = 25  

POIS = [
    (500, 500),   
    (2500, 500),  
    (1500, 1500), 
    (500, 2500),  
    (2800, 2800)  
]
POI_PAUSE_TIME = 3600 

class RubbleZone:
    def __init__(self, world_size):
        self.x = random.uniform(0, world_size)
        self.y = random.uniform(0, world_size)
        self.radius = random.uniform(30, 80)

class Node:
    def __init__(self, x, y, world_size):
        self.x = x
        self.y = y
        self.world_size = world_size
        self.has_message = False
        
        self.battery = random.uniform(30, 100) 
        self.survival_mode = False
        self.in_rubble = False
        
        self.wait_timer = 0
        self.target_x, self.target_y = random.choice(POIS)

    def move(self, speed):
        if self.wait_timer > 0:
            self.wait_timer -= 1
            return

        dist = math.hypot(self.target_x - self.x, self.target_y - self.y)
        
        if dist < speed:
            self.x = self.target_x
            self.y = self.target_y
            self.wait_timer = POI_PAUSE_TIME
            self.target_x, self.target_y = random.choice(POIS)
        else:
            self.x += (self.target_x - self.x) / dist * speed
            self.y += (self.target_y - self.y) / dist * speed
            
    def update_battery(self):
        if not self.survival_mode:
            self.battery -= 0.005 
            if self.battery <= 20.0:
                self.survival_mode = True

    def check_rubble(self, rubble_zones):
        self.in_rubble = False
        for r in rubble_zones:
            if abs(self.x - r.x) > r.radius or abs(self.y - r.y) > r.radius:
                continue
            if math.hypot(self.x - r.x, self.y - r.y) <= r.radius:
                self.in_rubble = True
                break

def is_in_range(node1, node2):
    dx = abs(node1.x - node2.x)
    if dx > COMM_RANGE: return False
    
    dy = abs(node1.y - node2.y)
    if dy > COMM_RANGE: return False
    
    eff_range = RUBBLE_RANGE if (node1.in_rubble or node2.in_rubble) else COMM_RANGE
    if dx > eff_range or dy > eff_range: return False
    
    return math.hypot(dx, dy) <= eff_range

def check_traditional_mesh_path(source, target, relays):
    all_nodes = [source, target] + [r for r in relays if not r.survival_mode]
    visited = set()
    queue = deque([source])
    visited.add(source)
    
    while queue:
        current = queue.popleft()
        if current == target:
            return True
            
        for neighbor in all_nodes:
            if neighbor not in visited:
                if is_in_range(current, neighbor):
                    visited.add(neighbor)
                    queue.append(neighbor)
    return False

def run_single_simulation(num_relay_nodes):
    rubble_zones = [RubbleZone(WORLD_SIZE) for _ in range(NUM_RUBBLE_ZONES)]
    
    source = Node(200, 200, WORLD_SIZE)
    source.has_message = True
    source.check_rubble(rubble_zones)
    
    target = Node(2800, 2800, WORLD_SIZE)
    target.check_rubble(rubble_zones)
    
    relays = [Node(random.uniform(0, WORLD_SIZE), random.uniform(0, WORLD_SIZE), WORLD_SIZE) 
              for _ in range(num_relay_nodes)]

    sf_delivery_time = None
    trad_delivery_time = None

    for step in range(MAX_SIM_TIME):
        # 1. Update State
        for r in relays:
            r.move(WALK_SPEED)
            r.update_battery()
            r.check_rubble(rubble_zones) # Cache state
            
        # 2. Check Traditional Mesh Baseline (Every 30 seconds)
        if trad_delivery_time is None and step % 30 == 0:
            if check_traditional_mesh_path(source, target, relays):
                trad_delivery_time = step / 60.0 

        # 3. Store-and-Forward Epidemic Routing 
        if sf_delivery_time is None:
            active_relays = [r for r in relays if not r.survival_mode]
            carriers = [source] + [r for r in active_relays if r.has_message]
            uninfected_relays = [r for r in active_relays if not r.has_message]
            
            nodes_to_infect = set()
            
            for carrier in carriers:
                if is_in_range(carrier, target):
                    sf_delivery_time = step / 60.0
                    break 
                    
                for relay in uninfected_relays:
                    if is_in_range(carrier, relay):
                        nodes_to_infect.add(relay)
                            
            for node in nodes_to_infect:
                node.has_message = True

        if sf_delivery_time is not None and trad_delivery_time is not None:
            break

    return sf_delivery_time, trad_delivery_time

def main():
    print("Starting BLAZING FAST CITY-SCALE DTN Simulation...")
    
    sf_avg_times, sf_stds, sf_success_rates = [], [], []
    trad_avg_times, trad_stds, trad_success_rates = [], [], []
    
    for count in RELAY_COUNTS_TO_TEST:
        print(f"\n--- Testing with {count} Survivors (Density) ---")
        sf_times, trad_times = [], []
        
        for _ in range(NUM_RUNS_PER_DENSITY):
            sf_time, trad_time = run_single_simulation(count)
            if sf_time is not None: sf_times.append(sf_time)
            if trad_time is not None: trad_times.append(trad_time)
            
        # Store-and-Forward Metrics
        sf_success = (len(sf_times) / NUM_RUNS_PER_DENSITY) * 100
        sf_success_rates.append(sf_success)
        sf_avg_times.append(np.mean(sf_times) if sf_times else None)
        sf_stds.append(np.std(sf_times) if sf_times else 0)

        # Traditional Mesh Metrics
        trad_success = (len(trad_times) / NUM_RUNS_PER_DENSITY) * 100
        trad_success_rates.append(trad_success)
        trad_avg_times.append(np.mean(trad_times) if trad_times else None)
        trad_stds.append(np.std(trad_times) if trad_times else 0)

        print(f"  Store & Forward -> Success: {sf_success:.0f}% | Avg Time: {sf_avg_times[-1] or 0:.1f} min")
        print(f"  Traditional     -> Success: {trad_success:.0f}% | Avg Time: {trad_avg_times[-1] or 0:.1f} min")

    # --- GENERATE DUAL PLOTS ---
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(16, 6))
    fig.suptitle("City-Scale Data Mule vs Traditional Mesh in Disaster Scenario", fontsize=14, fontweight='bold')

    ax1.plot(RELAY_COUNTS_TO_TEST, sf_success_rates, '-o', color='green', label="Store & Forward (Lifeline)")
    ax1.plot(RELAY_COUNTS_TO_TEST, trad_success_rates, '-s', color='red', label="Traditional Mesh")
    ax1.set_title("Message Delivery Success Rate")
    ax1.set_xlabel("Number of Relay Nodes (Density)")
    ax1.set_ylabel("Success Rate (%)")
    ax1.set_ylim(-5, 105)
    ax1.grid(True, linestyle="--", alpha=0.6)
    ax1.legend()

    valid_sf_x = [x for x, t in zip(RELAY_COUNTS_TO_TEST, sf_avg_times) if t is not None]
    valid_sf_t = [t for t in sf_avg_times if t is not None]
    valid_sf_e = [e for e, t in zip(sf_stds, sf_avg_times) if t is not None]
    
    ax2.errorbar(valid_sf_x, valid_sf_t, yerr=valid_sf_e, fmt='-o', color='green', capsize=5, label="Store & Forward")
    
    valid_tr_x = [x for x, t in zip(RELAY_COUNTS_TO_TEST, trad_avg_times) if t is not None]
    valid_tr_t = [t for t in trad_avg_times if t is not None]
    valid_tr_e = [e for e, t in zip(trad_stds, trad_avg_times) if t is not None]
    
    if valid_tr_x:
        ax2.errorbar(valid_tr_x, valid_tr_t, yerr=valid_tr_e, fmt='-s', color='red', capsize=5, label="Traditional Mesh")

    ax2.set_title("Average Time to Delivery")
    ax2.set_xlabel("Number of Relay Nodes (Density)")
    ax2.set_ylabel("Time (Minutes)")
    ax2.grid(True, linestyle="--", alpha=0.6)
    ax2.legend()

    plt.tight_layout()
    plt.savefig("simulation_results_city_scale_fast.png")
    print("\nPlot saved as 'simulation_results_city_scale_fast.png'")
    plt.show()

if __name__ == "__main__":
    main()