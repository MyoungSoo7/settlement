# 코딩 테스트 핵심 패턴 10선 (Java)

면접/코딩 테스트에서 반복적으로 출제되는 10가지 알고리즘 패턴을 정리한다.
모든 코드는 복사-붙여넣기 후 바로 실행 가능하다.

---

## 목차

| # | 패턴 | 대표 문제 |
|---|------|----------|
| 1 | [투 포인터](#1-투-포인터-two-pointer) | 정렬된 배열에서 두 수의 합 |
| 2 | [슬라이딩 윈도우](#2-슬라이딩-윈도우-sliding-window) | 최대 부분 합, 중복 없는 최장 부분 문자열 |
| 3 | [이진 탐색](#3-이진-탐색-binary-search) | Lower Bound (첫 등장 위치) |
| 4 | [BFS](#4-bfs-너비-우선-탐색) | 미로 최단 경로 |
| 5 | [DFS + 백트래킹](#5-dfs--백트래킹) | 순열/조합 생성, 섬의 개수 |
| 6 | [동적 프로그래밍](#6-동적-프로그래밍-dp) | 계단 오르기, LIS |
| 7 | [그리디](#7-그리디-greedy) | 회의실 배정, 동전 거스름돈 |
| 8 | [해시맵 활용](#8-해시맵-활용) | 두 수의 합 O(N), 아나그램 판별 |
| 9 | [스택/큐 활용](#9-스택큐-활용) | 유효한 괄호, 주식 가격 |
| 10 | [힙/우선순위 큐](#10-힙우선순위-큐) | K번째 큰 수, 데이터 스트림 중앙값 |

---

## 1. 투 포인터 (Two Pointer)

### 어떤 문제에 사용하나

- **정렬된** 배열에서 특정 조건을 만족하는 쌍(pair) 찾기
- 연속 부분 배열의 합/곱 조건 탐색
- 배열 내 중복 제거

> 핵심 아이디어: 양 끝(또는 같은 방향)에서 두 개의 포인터를 이동시키며 탐색 범위를 줄인다.

### 템플릿

```java
// 양끝 투 포인터 (opposite direction)
int left = 0, right = arr.length - 1;
while (left < right) {
    int sum = arr[left] + arr[right];
    if (sum == target) {
        // 정답 처리
        break;
    } else if (sum < target) {
        left++;   // 합이 작으면 왼쪽 포인터를 오른쪽으로
    } else {
        right--;  // 합이 크면 오른쪽 포인터를 왼쪽으로
    }
}
```

### 예제: 정렬된 배열에서 두 수의 합

```java
import java.util.*;

public class TwoSum {
    /**
     * 정렬된 배열에서 합이 target인 두 수의 인덱스를 반환한다.
     * 인덱스는 1-based.
     */
    public static int[] twoSum(int[] numbers, int target) {
        int left = 0, right = numbers.length - 1;

        while (left < right) {
            int sum = numbers[left] + numbers[right];

            if (sum == target) {
                // 1-based 인덱스로 반환
                return new int[]{left + 1, right + 1};
            } else if (sum < target) {
                left++;
            } else {
                right--;
            }
        }

        return new int[]{-1, -1}; // 답이 없는 경우
    }

    public static void main(String[] args) {
        int[] numbers = {2, 7, 11, 15};
        int target = 9;
        int[] result = twoSum(numbers, target);
        // 입력: [2, 7, 11, 15], target = 9
        // 출력: [1, 2]  (numbers[0] + numbers[1] = 2 + 7 = 9)
        System.out.println(Arrays.toString(result));
    }
}
```

### 복잡도

| | 값 |
|---|---|
| 시간 | O(N) |
| 공간 | O(1) |

---

## 2. 슬라이딩 윈도우 (Sliding Window)

### 어떤 문제에 사용하나

- 연속된 부분 배열/부분 문자열에서 최대/최소/조건 만족하는 구간 찾기
- 고정 크기 또는 가변 크기 윈도우
- "중복 없는 최장 부분 문자열" 같은 문제

> 핵심 아이디어: 윈도우(구간)를 오른쪽으로 한 칸씩 밀면서, 불필요한 재계산을 피한다.

### 템플릿

```java
// 가변 크기 슬라이딩 윈도우
int left = 0;
int best = 0;
// state: 윈도우 내부 상태를 관리하는 자료구조 (Map, Set 등)

for (int right = 0; right < arr.length; right++) {
    // 1. 오른쪽 원소를 윈도우에 추가
    // 2. 조건 위반 시 왼쪽을 줄인다
    while (/* 조건 위반 */) {
        // 왼쪽 원소를 윈도우에서 제거
        left++;
    }
    // 3. 현재 윈도우로 정답 갱신
    best = Math.max(best, right - left + 1);
}
```

### 예제 A: 크기 K인 부분 배열의 최대 합

```java
public class MaxSubarraySum {
    /**
     * 크기 k인 연속 부분 배열의 최대 합을 구한다.
     */
    public static int maxSum(int[] arr, int k) {
        // 첫 윈도우의 합 계산
        int windowSum = 0;
        for (int i = 0; i < k; i++) {
            windowSum += arr[i];
        }

        int maxSum = windowSum;

        // 윈도우를 한 칸씩 오른쪽으로 슬라이드
        for (int i = k; i < arr.length; i++) {
            windowSum += arr[i] - arr[i - k]; // 새 원소 추가, 맨 왼쪽 제거
            maxSum = Math.max(maxSum, windowSum);
        }

        return maxSum;
    }

    public static void main(String[] args) {
        int[] arr = {1, 4, 2, 10, 2, 3, 1, 0, 20};
        int k = 4;
        // 입력: arr = [1,4,2,10,2,3,1,0,20], k = 4
        // 출력: 24  (부분 배열 [2,3,1,0,20] 중 [1,0,20]... 아니라 [10,2,3,1]? → 아님)
        //       실제로 [0,20] 포함 구간: arr[5..8] = [3,1,0,20] = 24
        System.out.println(maxSum(arr, k)); // 24
    }
}
```

### 예제 B: 중복 없는 최장 부분 문자열 (LeetCode 3)

```java
import java.util.*;

public class LongestSubstring {
    /**
     * 중복 문자가 없는 가장 긴 부분 문자열의 길이를 반환한다.
     */
    public static int lengthOfLongestSubstring(String s) {
        Set<Character> window = new HashSet<>();
        int left = 0;
        int maxLen = 0;

        for (int right = 0; right < s.length(); right++) {
            char c = s.charAt(right);

            // 중복이 있으면 왼쪽을 줄여서 중복 제거
            while (window.contains(c)) {
                window.remove(s.charAt(left));
                left++;
            }

            window.add(c);
            maxLen = Math.max(maxLen, right - left + 1);
        }

        return maxLen;
    }

    public static void main(String[] args) {
        // 입력: "abcabcbb"
        // 출력: 3  ("abc")
        System.out.println(lengthOfLongestSubstring("abcabcbb")); // 3

        // 입력: "pwwkew"
        // 출력: 3  ("wke")
        System.out.println(lengthOfLongestSubstring("pwwkew"));   // 3
    }
}
```

### 복잡도

| | 값 |
|---|---|
| 시간 | O(N) |
| 공간 | O(K) — K는 윈도우 내 고유 원소 수 |

---

## 3. 이진 탐색 (Binary Search)

### 어떤 문제에 사용하나

- 정렬된 배열에서 특정 값 찾기
- **Lower Bound**: 특정 값 이상인 첫 위치
- **Upper Bound**: 특정 값 초과인 첫 위치
- 답의 범위가 단조(monotonic)일 때 **매개변수 탐색** (parametric search)

> 핵심 아이디어: 탐색 범위를 절반씩 줄인다. O(log N).

### 템플릿

```java
// Lower Bound: target 이상인 첫 인덱스
int lowerBound(int[] arr, int target) {
    int lo = 0, hi = arr.length;
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;  // 오버플로 방지
        if (arr[mid] < target) {
            lo = mid + 1;
        } else {
            hi = mid;
        }
    }
    return lo; // target이 없으면 삽입 위치 반환
}
```

### 예제: 특정 값의 첫 등장 위치

```java
import java.util.*;

public class BinarySearchLowerBound {
    /**
     * 정렬된 배열에서 target이 처음 등장하는 인덱스를 반환한다.
     * target이 없으면 -1을 반환한다.
     */
    public static int findFirst(int[] arr, int target) {
        int lo = 0, hi = arr.length;

        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            if (arr[mid] < target) {
                lo = mid + 1;
            } else {
                hi = mid;  // arr[mid] >= target이면 왼쪽으로
            }
        }

        // lo가 유효한 인덱스이고 값이 target인지 확인
        if (lo < arr.length && arr[lo] == target) {
            return lo;
        }
        return -1;
    }

    public static void main(String[] args) {
        int[] arr = {1, 2, 2, 2, 3, 4, 5};

        // 입력: arr = [1,2,2,2,3,4,5], target = 2
        // 출력: 1  (인덱스 1에서 처음 등장)
        System.out.println(findFirst(arr, 2)); // 1

        // 입력: target = 6
        // 출력: -1 (존재하지 않음)
        System.out.println(findFirst(arr, 6)); // -1
    }
}
```

### 복잡도

| | 값 |
|---|---|
| 시간 | O(log N) |
| 공간 | O(1) |

---

## 4. BFS (너비 우선 탐색)

### 어떤 문제에 사용하나

- **최단 거리/최소 횟수** 문제 (가중치가 동일할 때)
- 미로 탈출, 체스 나이트 이동 횟수
- 레벨 순서 탐색 (트리의 레벨 순회)

> 핵심 아이디어: 큐를 사용해 가까운 노드부터 탐색한다. 처음 도착한 시점이 최단 경로이다.

### 템플릿

```java
// 2D 그리드 BFS
int[][] dirs = {{0,1},{0,-1},{1,0},{-1,0}};
boolean[][] visited = new boolean[rows][cols];
Queue<int[]> queue = new LinkedList<>();

queue.offer(new int[]{startR, startC, 0}); // {행, 열, 거리}
visited[startR][startC] = true;

while (!queue.isEmpty()) {
    int[] cur = queue.poll();
    int r = cur[0], c = cur[1], dist = cur[2];

    if (r == endR && c == endC) return dist; // 도착

    for (int[] d : dirs) {
        int nr = r + d[0], nc = c + d[1];
        if (nr >= 0 && nr < rows && nc >= 0 && nc < cols
                && !visited[nr][nc] && grid[nr][nc] != 1) {
            visited[nr][nc] = true;
            queue.offer(new int[]{nr, nc, dist + 1});
        }
    }
}
return -1; // 도달 불가
```

### 예제: 미로 최단 경로

```java
import java.util.*;

public class MazeShortestPath {
    // 방향: 상하좌우
    static int[][] dirs = {{-1,0},{1,0},{0,-1},{0,1}};

    /**
     * 0은 통로, 1은 벽.
     * (0,0)에서 (rows-1, cols-1)까지 최단 거리를 반환한다.
     * 도달 불가하면 -1.
     */
    public static int shortestPath(int[][] maze) {
        int rows = maze.length, cols = maze[0].length;

        // 시작점이나 도착점이 벽이면 불가
        if (maze[0][0] == 1 || maze[rows-1][cols-1] == 1) return -1;

        boolean[][] visited = new boolean[rows][cols];
        Queue<int[]> queue = new LinkedList<>();

        queue.offer(new int[]{0, 0, 0});
        visited[0][0] = true;

        while (!queue.isEmpty()) {
            int[] cur = queue.poll();
            int r = cur[0], c = cur[1], dist = cur[2];

            // 도착 지점에 도달
            if (r == rows - 1 && c == cols - 1) {
                return dist;
            }

            for (int[] d : dirs) {
                int nr = r + d[0], nc = c + d[1];
                if (nr >= 0 && nr < rows && nc >= 0 && nc < cols
                        && !visited[nr][nc] && maze[nr][nc] == 0) {
                    visited[nr][nc] = true;
                    queue.offer(new int[]{nr, nc, dist + 1});
                }
            }
        }

        return -1; // 도달 불가
    }

    public static void main(String[] args) {
        int[][] maze = {
            {0, 0, 1, 0},
            {1, 0, 0, 0},
            {0, 0, 1, 0},
            {0, 0, 0, 0}
        };
        // 입력: 4x4 미로 (0=통로, 1=벽)
        // (0,0) → (3,3) 최단 경로
        // 출력: 6
        System.out.println(shortestPath(maze)); // 6
    }
}
```

### 복잡도

| | 값 |
|---|---|
| 시간 | O(V + E) — 그리드에서는 O(rows * cols) |
| 공간 | O(V) — visited 배열 + 큐 |

---

## 5. DFS + 백트래킹

### 어떤 문제에 사용하나

- **모든 경우의 수** 탐색: 순열, 조합, 부분집합
- 그래프 탐색: 섬의 개수, 연결 요소
- 제약 조건이 있는 탐색: N-Queen, 스도쿠

> 핵심 아이디어: 한 방향으로 끝까지 탐색 후, 선택을 취소(백트래킹)하고 다음 방향을 탐색한다.

### 템플릿

```java
// 조합/순열 백트래킹
void backtrack(List<List<Integer>> result, List<Integer> path,
               int[] nums, int start, boolean[] used) {
    if (path.size() == /* 목표 크기 */) {
        result.add(new ArrayList<>(path)); // 깊은 복사 필수
        return;
    }
    for (int i = start; i < nums.length; i++) {
        if (used[i]) continue; // 순열일 때
        path.add(nums[i]);
        used[i] = true;
        backtrack(result, path, nums, i + 1, used);
        path.remove(path.size() - 1); // 되돌리기
        used[i] = false;
    }
}
```

### 예제 A: 조합 생성 (nCr)

```java
import java.util.*;

public class Combinations {
    /**
     * 1~n에서 k개를 뽑는 모든 조합을 반환한다.
     */
    public static List<List<Integer>> combine(int n, int k) {
        List<List<Integer>> result = new ArrayList<>();
        backtrack(result, new ArrayList<>(), n, k, 1);
        return result;
    }

    private static void backtrack(List<List<Integer>> result,
                                   List<Integer> path, int n, int k, int start) {
        // k개를 모두 골랐으면 결과에 추가
        if (path.size() == k) {
            result.add(new ArrayList<>(path));
            return;
        }

        // 남은 수가 부족하면 가지치기 (pruning)
        for (int i = start; i <= n - (k - path.size()) + 1; i++) {
            path.add(i);
            backtrack(result, path, n, k, i + 1); // i+1: 중복 허용 안 함
            path.remove(path.size() - 1); // 백트래킹
        }
    }

    public static void main(String[] args) {
        // 입력: n=4, k=2
        // 출력: [[1,2],[1,3],[1,4],[2,3],[2,4],[3,4]]
        System.out.println(combine(4, 2));
    }
}
```

### 예제 B: 섬의 개수 (DFS)

```java
public class NumberOfIslands {
    static int[][] dirs = {{-1,0},{1,0},{0,-1},{0,1}};

    /**
     * '1'은 땅, '0'은 물. 연결된 땅 덩어리(섬)의 개수를 반환한다.
     */
    public static int numIslands(char[][] grid) {
        int count = 0;
        int rows = grid.length, cols = grid[0].length;

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (grid[r][c] == '1') {
                    count++;
                    dfs(grid, r, c, rows, cols); // 연결된 모든 땅을 방문 처리
                }
            }
        }
        return count;
    }

    private static void dfs(char[][] grid, int r, int c, int rows, int cols) {
        // 범위 밖이거나 물이면 종료
        if (r < 0 || r >= rows || c < 0 || c >= cols || grid[r][c] == '0') {
            return;
        }

        grid[r][c] = '0'; // 방문 표시 (물로 바꿈)

        for (int[] d : dirs) {
            dfs(grid, r + d[0], c + d[1], rows, cols);
        }
    }

    public static void main(String[] args) {
        char[][] grid = {
            {'1','1','0','0','0'},
            {'1','1','0','0','0'},
            {'0','0','1','0','0'},
            {'0','0','0','1','1'}
        };
        // 입력: 4x5 그리드
        // 출력: 3 (섬 3개)
        System.out.println(numIslands(grid)); // 3
    }
}
```

### 복잡도

| | 조합/순열 | 그리드 DFS |
|---|---|---|
| 시간 | O(N! 또는 2^N) | O(rows * cols) |
| 공간 | O(K) 재귀 깊이 | O(rows * cols) 최악 |

---

## 6. 동적 프로그래밍 (DP)

### 어떤 문제에 사용하나

- **최적 부분 구조** + **중복 부분 문제**가 있을 때
- "최소 비용", "경우의 수", "최대 길이" 같은 최적화 문제
- 대표 문제: 피보나치, 계단 오르기, 배낭 문제, LIS, LCS

> 핵심 아이디어: 작은 문제의 답을 저장(메모이제이션)해서 큰 문제를 푼다.

### DP 문제 풀이 순서

1. **상태 정의**: dp[i]가 무엇을 의미하는지 정한다
2. **점화식 도출**: dp[i]를 이전 값으로 표현한다
3. **초기값 설정**: 기저 조건(base case) 설정
4. **순서 결정**: 어떤 방향으로 채울지 결정
5. **답 도출**: dp 배열에서 답 추출

### 예제 A: 계단 오르기

```java
public class ClimbStairs {
    /**
     * 한 번에 1칸 또는 2칸 오를 수 있을 때,
     * n개의 계단을 오르는 방법의 수를 반환한다.
     *
     * dp[i] = i번째 계단에 도달하는 방법의 수
     * 점화식: dp[i] = dp[i-1] + dp[i-2]
     */
    public static int climbStairs(int n) {
        if (n <= 2) return n;

        // 공간 최적화: 직전 두 값만 기억
        int prev2 = 1; // dp[1]
        int prev1 = 2; // dp[2]

        for (int i = 3; i <= n; i++) {
            int cur = prev1 + prev2;
            prev2 = prev1;
            prev1 = cur;
        }

        return prev1;
    }

    public static void main(String[] args) {
        // 입력: n = 5
        // 출력: 8
        // 경로 예: 1+1+1+1+1, 1+1+1+2, 1+1+2+1, 1+2+1+1, 2+1+1+1,
        //          1+2+2, 2+1+2, 2+2+1
        System.out.println(climbStairs(5)); // 8
    }
}
```

### 예제 B: 최장 증가 부분 수열 (LIS)

```java
import java.util.*;

public class LIS {
    /**
     * 최장 증가 부분 수열의 길이를 반환한다.
     *
     * 방법 1: O(N^2) DP
     *   dp[i] = nums[i]를 마지막으로 하는 LIS 길이
     *   점화식: dp[i] = max(dp[j] + 1) (j < i, nums[j] < nums[i])
     *
     * 방법 2: O(N log N) 이진 탐색 (아래 구현)
     *   tails 배열: tails[i] = 길이 i+1인 증가 수열의 마지막 원소 최솟값
     */
    public static int lengthOfLIS(int[] nums) {
        // tails: 각 길이별 가능한 최소 마지막 값
        List<Integer> tails = new ArrayList<>();

        for (int num : nums) {
            // num이 들어갈 위치를 이진 탐색
            int pos = Collections.binarySearch(tails, num);

            if (pos < 0) {
                pos = -(pos + 1); // 삽입 위치
            }

            if (pos == tails.size()) {
                tails.add(num);    // 수열 길이 증가
            } else {
                tails.set(pos, num); // 더 작은 값으로 교체
            }
        }

        return tails.size();
    }

    public static void main(String[] args) {
        int[] nums = {10, 9, 2, 5, 3, 7, 101, 18};
        // 입력: [10, 9, 2, 5, 3, 7, 101, 18]
        // 출력: 4  (LIS: [2, 3, 7, 101] 또는 [2, 5, 7, 101] 등)
        System.out.println(lengthOfLIS(nums)); // 4
    }
}
```

### 복잡도

| 문제 | 시간 | 공간 |
|------|------|------|
| 계단 오르기 | O(N) | O(1) |
| LIS (DP) | O(N^2) | O(N) |
| LIS (이진 탐색) | O(N log N) | O(N) |

---

## 7. 그리디 (Greedy)

### 어떤 문제에 사용하나

- 매 순간 **현재 최선의 선택**이 전체 최적해가 되는 문제
- 정렬 후 순차적으로 결정
- 대표: 활동 선택 문제, 허프만 코딩, 최소 신장 트리

> 핵심 아이디어: "지금 이 순간 가장 좋은 것을 고르면 전체적으로도 최적이다"를 증명할 수 있어야 한다.

### 예제 A: 회의실 배정 (활동 선택)

```java
import java.util.*;

public class MeetingRoom {
    /**
     * 회의 시간이 겹치지 않게 최대한 많은 회의를 배정한다.
     * 전략: 끝나는 시간 기준으로 정렬 → 가장 빨리 끝나는 회의부터 선택
     */
    public static int maxMeetings(int[][] meetings) {
        // meetings[i] = {시작시간, 종료시간}
        // 종료 시간 기준 오름차순 정렬
        Arrays.sort(meetings, (a, b) -> a[1] - b[1]);

        int count = 0;
        int lastEnd = 0; // 마지막으로 선택한 회의의 종료 시간

        for (int[] meeting : meetings) {
            // 현재 회의의 시작 시간이 이전 회의의 종료 시간 이후이면 선택
            if (meeting[0] >= lastEnd) {
                count++;
                lastEnd = meeting[1];
            }
        }

        return count;
    }

    public static void main(String[] args) {
        int[][] meetings = {
            {1, 4}, {3, 5}, {0, 6}, {5, 7}, {3, 8}, {5, 9},
            {6, 10}, {8, 11}, {8, 12}, {2, 13}, {12, 14}
        };
        // 입력: 11개 회의
        // 출력: 4 (예: [1,4], [5,7], [8,11], [12,14])
        System.out.println(maxMeetings(meetings)); // 4
    }
}
```

### 예제 B: 동전 거스름돈 (최소 동전 수)

```java
public class CoinChange {
    /**
     * 거스름돈을 최소 동전 수로 만든다.
     * 단, 그리디가 최적해를 보장하는 경우는 동전 단위가
     * 서로 배수 관계일 때뿐이다. (예: 500, 100, 50, 10)
     * 일반적인 경우에는 DP를 사용해야 한다.
     */
    public static int minCoinsGreedy(int amount) {
        int[] coins = {500, 100, 50, 10}; // 한국 동전 단위
        int count = 0;

        for (int coin : coins) {
            count += amount / coin; // 현재 동전으로 낼 수 있는 최대 수
            amount %= coin;         // 나머지
        }

        return (amount == 0) ? count : -1; // 거슬러 줄 수 없으면 -1
    }

    public static void main(String[] args) {
        // 입력: 1260원
        // 출력: 6 (500*2 + 100*2 + 50*1 + 10*1)
        System.out.println(minCoinsGreedy(1260)); // 6
    }
}
```

### 복잡도

| | 값 |
|---|---|
| 시간 | O(N log N) — 정렬이 지배적 |
| 공간 | O(1) (인플레이스 정렬 시) |

---

## 8. 해시맵 활용

### 어떤 문제에 사용하나

- **O(1) 조회**가 필요한 문제
- 두 수의 합 (정렬 안 된 배열)
- 빈도수 세기, 아나그램 판별, 중복 찾기
- 부분합(prefix sum)과 결합

> 핵심 아이디어: "이전에 본 값"을 해시맵에 저장해서 현재 값과 짝을 맞춘다.

### 예제 A: 두 수의 합 (정렬 안 된 배열)

```java
import java.util.*;

public class TwoSumHash {
    /**
     * 배열에서 합이 target인 두 수의 인덱스를 반환한다.
     * 정렬되지 않은 배열에서 O(N)으로 해결.
     *
     * 아이디어: nums[i]를 볼 때, target - nums[i]를 이전에 봤는지 확인
     */
    public static int[] twoSum(int[] nums, int target) {
        // 값 → 인덱스 매핑
        Map<Integer, Integer> map = new HashMap<>();

        for (int i = 0; i < nums.length; i++) {
            int complement = target - nums[i]; // 짝이 되는 수

            if (map.containsKey(complement)) {
                return new int[]{map.get(complement), i};
            }

            map.put(nums[i], i); // 현재 값 저장
        }

        return new int[]{-1, -1};
    }

    public static void main(String[] args) {
        int[] nums = {2, 7, 11, 15};
        int target = 9;
        // 입력: nums = [2,7,11,15], target = 9
        // 출력: [0, 1]  (nums[0] + nums[1] = 2 + 7 = 9)
        System.out.println(Arrays.toString(twoSum(nums, target))); // [0, 1]
    }
}
```

### 예제 B: 아나그램 판별

```java
import java.util.*;

public class Anagram {
    /**
     * 두 문자열이 아나그램인지 판별한다.
     * 아나그램: 같은 문자를 재배열해서 만들 수 있는 문자열
     *
     * 방법: 각 문자의 빈도수가 동일한지 비교
     */
    public static boolean isAnagram(String s, String t) {
        if (s.length() != t.length()) return false;

        // 알파벳 소문자만 있다면 int[26] 배열이 더 빠름
        int[] count = new int[26];

        for (int i = 0; i < s.length(); i++) {
            count[s.charAt(i) - 'a']++; // s의 문자는 +1
            count[t.charAt(i) - 'a']--; // t의 문자는 -1
        }

        // 모든 카운트가 0이면 아나그램
        for (int c : count) {
            if (c != 0) return false;
        }
        return true;
    }

    public static void main(String[] args) {
        // 입력: "anagram", "nagaram"
        // 출력: true
        System.out.println(isAnagram("anagram", "nagaram")); // true

        // 입력: "rat", "car"
        // 출력: false
        System.out.println(isAnagram("rat", "car")); // false
    }
}
```

### 복잡도

| | 값 |
|---|---|
| 시간 | O(N) |
| 공간 | O(N) — 해시맵 크기 |

---

## 9. 스택/큐 활용

### 어떤 문제에 사용하나

- **괄호 매칭**: 유효한 괄호, 수식 계산
- **단조 스택(Monotone Stack)**: 다음 큰 원소, 주식 가격 스팬
- **큐**: BFS (4번 참고), 캐시 구현 (LRU)

> 핵심 아이디어: 스택은 "가장 최근 것부터 처리", 큐는 "가장 오래된 것부터 처리".

### 예제 A: 유효한 괄호

```java
import java.util.*;

public class ValidParentheses {
    /**
     * 괄호 문자열이 올바르게 짝지어져 있는지 확인한다.
     * '(', ')', '{', '}', '[', ']' 만 포함.
     *
     * 전략: 여는 괄호 → 스택에 push, 닫는 괄호 → pop해서 짝 확인
     */
    public static boolean isValid(String s) {
        Deque<Character> stack = new ArrayDeque<>();

        for (char c : s.toCharArray()) {
            // 여는 괄호면 대응하는 닫는 괄호를 push
            if (c == '(') stack.push(')');
            else if (c == '{') stack.push('}');
            else if (c == '[') stack.push(']');
            else {
                // 닫는 괄호인데 스택이 비었거나 짝이 안 맞으면 false
                if (stack.isEmpty() || stack.pop() != c) {
                    return false;
                }
            }
        }

        return stack.isEmpty(); // 스택이 비어야 모든 괄호가 매칭됨
    }

    public static void main(String[] args) {
        // 입력: "()[]{}"  → 출력: true
        System.out.println(isValid("()[]{}")); // true

        // 입력: "(]"  → 출력: false
        System.out.println(isValid("(]")); // false

        // 입력: "{[]}"  → 출력: true
        System.out.println(isValid("{[]}")); // true
    }
}
```

### 예제 B: 주식 가격 스팬 (단조 스택)

```java
import java.util.*;

public class StockSpan {
    /**
     * 각 날의 주가 스팬을 계산한다.
     * 스팬 = 현재 날로부터 연속으로 현재 가격 이하인 날의 수 (당일 포함)
     *
     * 단조 스택 사용: 스택에 인덱스를 저장하고,
     * 현재 가격보다 작거나 같은 가격의 인덱스는 pop한다.
     */
    public static int[] calculateSpan(int[] prices) {
        int n = prices.length;
        int[] span = new int[n];
        Deque<Integer> stack = new ArrayDeque<>(); // 인덱스 저장

        for (int i = 0; i < n; i++) {
            // 현재 가격 이하인 이전 날들을 모두 pop
            while (!stack.isEmpty() && prices[stack.peek()] <= prices[i]) {
                stack.pop();
            }

            // 스택이 비었으면 처음부터 현재까지 전부 스팬
            span[i] = stack.isEmpty() ? (i + 1) : (i - stack.peek());
            stack.push(i);
        }

        return span;
    }

    public static void main(String[] args) {
        int[] prices = {100, 80, 60, 70, 60, 75, 85};
        // 입력: [100, 80, 60, 70, 60, 75, 85]
        // 출력: [1, 1, 1, 2, 1, 4, 6]
        //   100 → 1 (자기 자신)
        //    80 → 1
        //    60 → 1
        //    70 → 2 (70, 60)
        //    60 → 1
        //    75 → 4 (75, 60, 70, 60)
        //    85 → 6 (85, 75, 60, 70, 60, 80)
        System.out.println(Arrays.toString(calculateSpan(prices)));
    }
}
```

### 복잡도

| | 값 |
|---|---|
| 시간 | O(N) — 각 원소는 최대 1번 push, 1번 pop |
| 공간 | O(N) — 스택 크기 |

---

## 10. 힙/우선순위 큐

### 어떤 문제에 사용하나

- **K번째 크기** 문제 (K번째 큰 수, K개 가장 빈번한 원소)
- **스트림에서 중앙값** 유지
- **다익스트라** 최단 경로 (가중치 그래프)
- 우선순위가 있는 작업 스케줄링

> 핵심 아이디어: 삽입/삭제가 O(log N)이면서 최소/최대를 O(1)에 조회한다.

### Java 힙 기본 사용법

```java
// 최소 힙 (기본)
PriorityQueue<Integer> minHeap = new PriorityQueue<>();

// 최대 힙
PriorityQueue<Integer> maxHeap = new PriorityQueue<>(Collections.reverseOrder());

minHeap.offer(5);    // 삽입: O(log N)
minHeap.peek();      // 최솟값 조회: O(1)
minHeap.poll();      // 최솟값 추출: O(log N)
```

### 예제 A: K번째 큰 수

```java
import java.util.*;

public class KthLargest {
    /**
     * 배열에서 K번째로 큰 수를 반환한다.
     *
     * 전략: 크기 K인 최소 힙을 유지한다.
     * 힙에 K개 초과 원소가 들어오면 가장 작은 것을 빼낸다.
     * 최종적으로 힙의 루트(최솟값)가 K번째로 큰 수이다.
     */
    public static int findKthLargest(int[] nums, int k) {
        PriorityQueue<Integer> minHeap = new PriorityQueue<>();

        for (int num : nums) {
            minHeap.offer(num);

            // 힙 크기가 k를 초과하면 가장 작은 수 제거
            if (minHeap.size() > k) {
                minHeap.poll();
            }
        }

        return minHeap.peek(); // k개 중 가장 작은 수 = 전체에서 k번째 큰 수
    }

    public static void main(String[] args) {
        int[] nums = {3, 2, 1, 5, 6, 4};
        int k = 2;
        // 입력: nums = [3,2,1,5,6,4], k = 2
        // 출력: 5  (정렬하면 [1,2,3,4,5,6], 2번째로 큰 수 = 5)
        System.out.println(findKthLargest(nums, k)); // 5
    }
}
```

### 예제 B: 데이터 스트림의 중앙값

```java
import java.util.*;

public class MedianFinder {
    // 왼쪽 절반: 최대 힙 (작은 수들의 최댓값을 빠르게 접근)
    private PriorityQueue<Integer> maxHeap = new PriorityQueue<>(Collections.reverseOrder());
    // 오른쪽 절반: 최소 힙 (큰 수들의 최솟값을 빠르게 접근)
    private PriorityQueue<Integer> minHeap = new PriorityQueue<>();

    /**
     * 새로운 수를 추가한다.
     *
     * 불변식:
     * 1. maxHeap.size() == minHeap.size() 또는 maxHeap.size() == minHeap.size() + 1
     * 2. maxHeap의 모든 수 <= minHeap의 모든 수
     */
    public void addNum(int num) {
        maxHeap.offer(num); // 일단 왼쪽에 추가

        // 왼쪽 최대가 오른쪽 최소보다 크면 밸런싱
        minHeap.offer(maxHeap.poll());

        // 크기 조정: 왼쪽이 오른쪽보다 같거나 1개 더 많아야 함
        if (minHeap.size() > maxHeap.size()) {
            maxHeap.offer(minHeap.poll());
        }
    }

    /**
     * 현재까지의 중앙값을 반환한다.
     */
    public double findMedian() {
        if (maxHeap.size() > minHeap.size()) {
            return maxHeap.peek(); // 홀수 개: 왼쪽 힙의 루트
        }
        return (maxHeap.peek() + minHeap.peek()) / 2.0; // 짝수 개: 두 힙의 루트 평균
    }

    public static void main(String[] args) {
        MedianFinder mf = new MedianFinder();

        mf.addNum(1);
        // 중앙값: 1.0
        System.out.println(mf.findMedian()); // 1.0

        mf.addNum(2);
        // 중앙값: (1 + 2) / 2 = 1.5
        System.out.println(mf.findMedian()); // 1.5

        mf.addNum(3);
        // 중앙값: 2.0
        System.out.println(mf.findMedian()); // 2.0

        mf.addNum(4);
        // 중앙값: (2 + 3) / 2 = 2.5
        System.out.println(mf.findMedian()); // 2.5
    }
}
```

### 복잡도

| | K번째 큰 수 | 데이터 스트림 중앙값 |
|---|---|---|
| 시간 | O(N log K) | addNum O(log N), findMedian O(1) |
| 공간 | O(K) | O(N) |

---

## 빠른 참조표

| # | 패턴 | 핵심 자료구조 | 시간 복잡도 | 사용 신호 |
|---|------|-------------|-----------|---------|
| 1 | 투 포인터 | 배열 | O(N) | 정렬된 배열, 쌍 찾기 |
| 2 | 슬라이딩 윈도우 | 배열 + Set/Map | O(N) | 연속 부분 배열/문자열 |
| 3 | 이진 탐색 | 정렬 배열 | O(log N) | 정렬 + 탐색, 단조 조건 |
| 4 | BFS | 큐 | O(V+E) | 최단 거리, 레벨 탐색 |
| 5 | DFS + 백트래킹 | 재귀 + 스택 | O(2^N) or O(N!) | 모든 경우의 수, 연결 요소 |
| 6 | DP | 배열 | 문제마다 다름 | 최적 부분 구조 + 중복 |
| 7 | 그리디 | 정렬 | O(N log N) | 현재 최선 = 전체 최선 |
| 8 | 해시맵 | HashMap | O(N) | O(1) 조회 필요, 빈도수 |
| 9 | 스택/큐 | Stack/Deque | O(N) | 짝 매칭, 단조 스택 |
| 10 | 힙 | PriorityQueue | O(N log K) | K번째, 중앙값, 스케줄링 |

---

## 문제 유형별 패턴 선택 가이드

```
"두 수의 합" 류
  └─ 정렬됨? → 투 포인터 (1)
  └─ 정렬 안 됨? → 해시맵 (8)

"연속 부분 배열/문자열" 류
  └─ 고정 크기? → 슬라이딩 윈도우 (2)
  └─ 가변 크기? → 슬라이딩 윈도우 (2) + 투 포인터 (1)

"최단 거리" 류
  └─ 가중치 동일? → BFS (4)
  └─ 가중치 다름? → 다익스트라 = BFS + 힙 (10)

"모든 경우의 수" 류
  └─ 순서 중요(순열)? → DFS + 백트래킹 (5)
  └─ 순서 무관(조합)? → DFS + 백트래킹 (5)
  └─ 경우의 수만 세기? → DP (6)

"최소/최대 비용" 류
  └─ 최적 부분 구조? → DP (6)
  └─ 현재 최선 = 전체 최선 증명 가능? → 그리디 (7)

"K번째" 류
  └─ → 힙 (10)

"괄호/짝 매칭" 류
  └─ → 스택 (9)

"정렬 배열에서 위치 찾기" 류
  └─ → 이진 탐색 (3)
```
