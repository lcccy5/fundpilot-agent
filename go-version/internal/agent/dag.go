package agent

import (
	"context"
	"errors"
	"fmt"
	"sync"
)

type Task struct {
	ID        string
	DependsOn []string
	Run       func(context.Context) (any, error)
}

type TaskResult struct {
	Value any
	Err   error
}

func ExecuteDAG(ctx context.Context, tasks []Task, maxParallel int) (map[string]TaskResult, error) {
	if len(tasks) == 0 {
		return map[string]TaskResult{}, nil
	}
	if len(tasks) > 20 {
		return nil, errors.New("plan exceeds 20 tasks")
	}
	if maxParallel <= 0 {
		maxParallel = 4
	}
	byID := map[string]Task{}
	indegree := map[string]int{}
	dependents := map[string][]string{}
	for _, task := range tasks {
		if task.ID == "" || task.Run == nil {
			return nil, errors.New("task ID and runner are required")
		}
		if _, exists := byID[task.ID]; exists {
			return nil, fmt.Errorf("duplicate task: %s", task.ID)
		}
		byID[task.ID] = task
		indegree[task.ID] = len(task.DependsOn)
	}
	for _, task := range tasks {
		for _, dependency := range task.DependsOn {
			if _, exists := byID[dependency]; !exists {
				return nil, fmt.Errorf("unknown dependency: %s", dependency)
			}
			dependents[dependency] = append(dependents[dependency], task.ID)
		}
	}
	ready := make([]string, 0)
	for id, degree := range indegree {
		if degree == 0 {
			ready = append(ready, id)
		}
	}
	results := map[string]TaskResult{}
	completed := 0
	for len(ready) > 0 {
		batch := ready
		ready = nil
		semaphore := make(chan struct{}, maxParallel)
		var wg sync.WaitGroup
		var mu sync.Mutex
		for _, id := range batch {
			id := id
			wg.Add(1)
			go func() {
				defer wg.Done()
				semaphore <- struct{}{}
				defer func() { <-semaphore }()
				value, err := byID[id].Run(ctx)
				mu.Lock()
				results[id] = TaskResult{Value: value, Err: err}
				mu.Unlock()
			}()
		}
		wg.Wait()
		for _, id := range batch {
			completed++
			for _, next := range dependents[id] {
				indegree[next]--
				if indegree[next] == 0 {
					ready = append(ready, next)
				}
			}
		}
	}
	if completed != len(tasks) {
		return nil, errors.New("plan contains a cycle")
	}
	return results, nil
}
