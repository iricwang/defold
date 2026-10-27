;; Copyright 2020-2026 The Defold Foundation
;; Copyright 2014-2020 King
;; Copyright 2009-2014 Ragnar Svensson, Christian Murray
;; Licensed under the Defold License version 1.0 (the "License"); you may not use
;; this file except in compliance with the License.
;;
;; You may obtain a copy of the License, together with FAQs at
;; https://www.defold.com/license
;;
;; Unless required by applicable law or agreed to in writing, software distributed
;; under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
;; CONDITIONS OF ANY KIND, either express or implied. See the License for the
;; specific language governing permissions and limitations under the License.

(ns editor.skeletal-animation-native-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-native :as native]
            [util.coll :as coll])
  (:import [java.nio ByteBuffer]
           [java.util Arrays Base64]))

;; These first-party fixtures were authored from minimal JSON and saved by
;; Spine 4.2.43 solely as research oracles. Tests never invoke Spine and contain
;; no original user project data.
(def ^:private fixtures
  {:minimal "XVFJTgJREH2vuxlkJhhBhqaZOoDaGNN7oyfwChiHkKgkKIkr4j04hCRewouw1oV7rfoutPsn9arq1atK/f/DjbfxwuAkCN/IentE/DsVoEhm6fc4vGMCsJEoEoRl2wwms/vp7fXja0GoDiYMzt+B1gVlxFFMXTdsV3qmy6vZx2GsXBVfEhuTvJk9Py0X27bp8AxmpGRxEGvqi8+L7Yk5smaOjcLpi/TIEC7m86+8Zi4t7utdEgpVBRtprdQ1ziq0kFNXdrler7/bMi+peaNSU5dScUajHQXHvIzd5OX8YbuCazaElZSuY44E02INwzYN+rG9azhASuIVOtHXNlsVopyPMayzT2CXv59UVtEoN2QqKiwqX4pyDrrKNtn3CVdsAE+JPHpsScrsn/YH"
   :fields "XVHLSsNQFJxJ+rLvUrG1jzR9hbZqKtp9ifgBLvyBgg8CSqFacFX8AL9B/AMLbt248zPcuFLQhUsXnntdaBKYc27mzBwmN6OlvbRH7o47eiArjT7x7ykCOTJFp83eGaOAiWiOIAzTpDv0zyenxxf3WaGaGNLdewbqB5QVWyF1RbMt8UzmR/7HZmhckp4XDEie+FeX89lrQztsXZMyMtgNmTrSM4I1QURiplnNjl/EI0s4m06/MuM7wKKBde8biKoPKnnvakVifA1UFJF6upXMSHuPQMFiobW735B9MTWrFsuqxZU46d0AK94hENE3Y9bovy1g6XwwYuLZZl9qQlDVbE1XJ5S6jA3E5bxAM3jXOlM2yDkYwPA+gVX+/qKCEvXTPcaDwpzi80EugpZia+w4hCXowlZEBm3W5ZWpP+0P"
   :two-bones "jVLLTsJQEJ3T8hIKhWAEeZTyagC1GMPe4Bf4C/hugjRBSVwR/4ONfyCJP+GHyFoX7nXmmghtYmKTOTNz5nHaeztY2kt74B65gxegVOuCNp48kQmk4DTRGSNKpFPUBIE0XYfb925H15d3zxmm6tSHe/JKVD0FrzgIdZcU2+CZ0ezCe98PlQvss2w9AFfew/1suqqpCVthkksa2qGhFvs02w5bxIRmoJw5fuQZXoKp73+mJbOgYVe+JSpQENApIZWSxCmBKhnichYWi8VXjffFJC/ni+Li0pyUaEsgok5Gr+DMn6zm9KuaB85vvPHbn6rGhurw6X+qQ5tVhx9r1cKcLHUmpMV44hBdxgRbWbEVhU7opIq0R3GO51QP3q96o0yQc6hHmkhu4+e3yElT1+ggHmw0hc8GuQg1hK2g5YAstjbZQqSpiSqnSK17vwE="
   :unicode "rVLLSsNQEJ2TpE1t06alYmsfafoKbdVUpHvRL/AXKj4o+IBqwVUR/AH33bgUQSy48g/8kQiu9A905gpqsnBlYM7MPXNmJpmbwdyduwN/wx88AqVaF/TryRPZQApeE50jxIh0itkgkKbr8Puj4+Hh/tlDhqk69eFvPxNVd8At1iLqkmIbXDOc7I3eViPpAvssWw/AwejifDIOaqrCVZjklIZ2pKjFPs22xGbY0CyUM5uXXMNNcPVy8/R6d5sWwoGGZfmcmEBBQKeEZEoSpwSqZInLOZjNZh81bhmXczlfFGeKOCnRgoChlqNXsHt6Ekzpe3Ce5wb313/Ntf5jbmFKjloMaXGuWEeXMcFWVmxFoRdZV5FWyOR4SvXwJas3yoQ5j3qkbb0TLeLr38iJqGt1YIaFtvDZMGdQQ9gKWh7IYWuTK0SamqjyEakf7Sc="
   :one-char "rVJbTsJQED2nBUEoFIIR5FHKqwHUYgz/Rt2AcQcYHyHxkagkfhHjNliEJG7CjfCtO9CZa6KWxD+bnDNzz7zauR3O/bk/DHfD4QtZrveIX08BcMk0gxa7l4wDNuIuQVi2zXAwvhpdnN09Z0VqYMDw4BWoHVFabC9ll43alJrR5HT8trUULorNCfokz8cP95PbRd1U+IZTErLYWSpqi80I1gUxl5bDSnbvUWqkCZ+OM+p7tLihXxJXKirZSGqkrH5aqQZHTd7jbDb7qEu3FT1XCiU1CU1OqbeqFDN7sas8ubleTPE9syAjD/8c6fzHyOIUnlkHrBWp2GFPOCmoGLVqOFhaUgmbSIg/RSN6teaNslEtQB/W/juwxq8/Iq9JPafLRDTRVT0X1WJoqlplOyA8QQe+Chm0WJMj0z+5nw=="})

(defn- fixture [name]
  (.decode (Base64/getDecoder) ^String (get fixtures name)))

;; Unmodified projects retain every compressed byte, including undecoded state.
(deftest independent-native-preservation-test
  (doseq [[name _] fixtures]
    (testing (str name)
      (let [source (fixture name) decoded (native/decode source)]
        (is (Arrays/equals ^bytes source ^bytes (native/encode decoded)))
        (is (= ["40" " 0" " 4.2.43"] (:header decoded)))
        (is (false? (:complete decoded)))
        (is (:opaque-data-preserved decoded))))))

;; Eight editable identities come from a native all-fields differential oracle.
(deftest typed-native-bone-fields-test
  (let [bone (first (:bones (native/decode (fixture :fields))))]
    (is (= "root" (:name bone)))
    (is (= {:length 13.25 :x 17.5 :y -19.75 :rotation 23.5
            :scale-x 1.25 :scale-y 1.75 :shear-x 29.5 :shear-y 31.5}
           (:properties bone)))
    (is (false? (get-in bone [:fields 2 :value])))
    (is (= 0x11223344 (get-in bone [:fields 17 :value :rgba])))
    (is (= "ik" (get-in bone [:fields 28 :value :value])))))

;; Short ASCII and UTF-16-length strings decode without guessing reference IDs.
(deftest native-bone-names-and-parent-references-test
  (doseq [[fixture-name expected] [[:two-bones ["root" "child"]]
                                  [:unicode ["根骨" "子骨"]]
                                  [:one-char ["R" "C"]]]]
    (let [bones (:bones (native/decode (fixture fixture-name)))]
      (is (= expected (mapv :name bones)))
      (is (= [0 12] (mapv :parent-reference bones)))
      (is (= {:reference 19} (get-in bones [1 :fields 27 :value]))))))

;; An edit changes only its four-byte field; every opaque native object survives.
(deftest native-transform-write-preserves-opaque-data-test
  (let [project (native/decode (fixture :two-bones))
        updated (native/set-bone-properties project 1 {:x -42.25 :rotation 37.5})
        reopened (native/decode (native/encode updated))
        original-payload ^bytes (::native/payload project)
        rewritten-payload ^bytes (::native/payload reopened)
        expected (aclone original-payload)
        expected-buffer (ByteBuffer/wrap expected)
        x-offset (get-in project [:bones 1 :fields 10 :offset])
        rotation-offset (get-in project [:bones 1 :fields 12 :offset])]
    (.putFloat expected-buffer (int x-offset) (float -42.25))
    (.putFloat expected-buffer (int rotation-offset) (float 37.5))
    (is (Arrays/equals ^bytes expected rewritten-payload))
    (is (= -42.25 (get-in reopened [:bones 1 :properties :x])))
    (is (= 37.5 (get-in reopened [:bones 1 :properties :rotation])))
    (is (= 10.0 (get-in project [:bones 1 :properties :x])))
    (is (= (mapv :name (:bones project)) (mapv :name (:bones reopened))))))

;; Every verified transform field can be edited without rewriting neighboring data.
(deftest native-all-transform-fields-write-test
  (let [project (native/decode (fixture :fields))]
    (doseq [[property tag] [[:length 9] [:x 10] [:y 11] [:rotation 12]
                           [:scale-x 13] [:scale-y 14] [:shear-x 23] [:shear-y 25]]]
      (let [updated (native/set-bone-properties project 0 {property -11.25})
            reopened (native/decode (native/encode updated))
            expected (aclone ^bytes (::native/payload project))]
        (.putFloat (ByteBuffer/wrap expected) (int (get-in project [:bones 0 :fields tag :offset])) (float -11.25))
        (is (= -11.25 (get-in reopened [:bones 0 :properties property])))
        (is (Arrays/equals expected ^bytes (::native/payload reopened)))))))

;; An unrecognized field inside a matching bone record must fail instead of being skipped.
(deftest native-unknown-bone-field-test
  (let [project (native/decode (fixture :minimal))
        payload (aclone ^bytes (::native/payload project))
        value-offset (get-in project [:bones 0 :fields 13 :offset])]
    (aset-byte payload (dec value-offset) (byte 99))
    (is (thrown? clojure.lang.ExceptionInfo (native/decode (#'native/pack payload))))))

;; Unsupported versions, malformed streams, and full import fail explicitly.
(deftest native-unsupported-data-test
  (let [project (native/decode (fixture :minimal))
        payload (aclone ^bytes (::native/payload project))
        source (fixture :minimal)]
    (aset-byte payload 9 (byte 53))
    (is (thrown? clojure.lang.ExceptionInfo (native/decode (#'native/pack payload))))
    (is (thrown? clojure.lang.ExceptionInfo (native/decode (Arrays/copyOf ^bytes source 10))))
    (is (thrown? clojure.lang.ExceptionInfo (native/decode (Arrays/copyOf ^bytes source (inc (alength ^bytes source))))))
    (is (thrown? clojure.lang.ExceptionInfo (native/require-complete-document! project)))
    (is (thrown? clojure.lang.ExceptionInfo (native/decode (byte-array 0))))))

;; Only finite supported transform edits are accepted, never opaque field writes.
(deftest native-edit-boundaries-test
  (let [project (native/decode (fixture :minimal))]
    (doseq [[bone-index changes] [[-1 {:x 0}] [1 {:x 0}] [0 {:name "renamed"}]
                                 [0 {:x Double/NaN}] [0 {:x Double/POSITIVE_INFINITY}]
                                 [0 {:x 1.0e100}] [0 {:x "1"}] [0 nil]]]
      (is (thrown? clojure.lang.ExceptionInfo (native/set-bone-properties project bone-index changes))))))

(def ^:private graph-fixtures
  {   :translatex "dVLbTsJAEJ3T0pZLW0CMINdyawAVjOFd8Af0FzBeQgJiCBifiA/6FfwEiT/hj/Csib7rzPqANHGTc2bn7JnuZLrdlbfyuu2TdvcVyJaaoD8rRRQHYvCraIxgEOlkxEEgTdfR7gzHg9vrT5eVMnXQPnsjKlyAv3AUMGeVWuGSwfxq+H4YOE5zTDBaAG6Gj7P5dF1SFZ7iKB9pqG+KsINIeAbYyLmnT2zjOkwnky9HsiI07Ev3hlBaSKewnGRlHxMqkC0hWcRyufwu8QWm5LlERoIl5qjsIkIhNQs9j8vJ3XrBHevAePKwNqGTNYpp3FAKRggmwSFb0mfAdaG9cIhLbY9xHoBaytG/Z0fvH0eEDMiMatyjw9hjhPgK2ymq4ZBmsnKMJnOYkVNqXrEfmHOGDsiS8dkLKm//aTUfd1vzqUVa/4NoF78PJCmmpt2AtW2Mi57Y1kJUETWPmg8qMurkieBQFQVOEdt4fwA="
   :translatey "dVLJTgJBEK03C9vMsIgRZB22CaAOxnAX/AH9BYxLSEAMAeOJeNCv4CdI/Al/hLMmeteq9oBDYievquv1q+nKm+6u3JXb9U/87iuQK7dAf1aaKAFY8GpojmAS6WQmQCBN1+F3huPB7fVnnJkKdeCfvREVL8BfONoS5xRb5ZbB/Gr4frh1nOGcZLQB3AwfZ/Ppuqw6XBVjfKShsWnCDqKRGWAjHz99Yhn3YTqZfDlSlaBhX6Y3JWQk6BSRk5zsLQlFsiWlSlgul99lviAkdT6ZlRQWcUx2UQmG8kIv4HJyt17wxDownjysQ9ApPLI0HigN00CI4JAt5TPArmgvkqS3xzjfglpK0b9nRe8fRZRMiEd1ntFh7DEMvsJ2Ssoc0kLMHKPFMcLIK7agorflc5YOKCz22QuqBP+08ice5Dxqk9b/INrF7wNJiahlNxEOChPCJ4OcQVVhC6h7oBKjQa4QDtVQ5BLWRvsD"
   :scalex "dVJLTgJBEK03Pz4zA4MYQf7/ACoYw17wAnoFjJ+QgGMIGFfEhZ6CS5B4CS/CWhPda1W7QCaxk/eq69Wr6U5N91alVanXOen0XoFMsQX6s5JEccBGo4rmGCaRTmYcBNJ0HZ3uaDK8vf6MsVKmLjpnb0T5C/AXjgLmjFIr3DKcX43eDwPlFEeP0QZwM3qczafrouooKY5ySUN904QdRMIzwEE2dvrENu7D1Pe/XMkK0LAvtzeFUkI6haWSkb0tlCdHQqKA5XL5XeQDLMmzXlpCSMxR2UWEDDULPYdL/269II10YOI/rC3oFBrbGl8oCdOARXDJkfQZiHnQXjjEpbfPOA9ALeUY3LOj/48jQiZkRjW+o8vYYxh8hOMW1HBIs1g5Ros5zMgqNae4EZhzmg4oJONzFlTe/tNqPrFtrUFt0gYfRLv4fSAJMbWcJkLbxrjo3rZmUEXUHGoNUIFRp5IILlWR5xT2xvsD"
   :scaley "dVLJTgJBEK03G8vMsIgRZBl2AqiDMdwFf0B/AeMSEnAMAeOJeNCv4CdI/Al/hLMmeteq9oCQ2Ml71fXq1XSnprvL0rLU9U/87iuQLbZAf1aKKA7YaFTRHMEk0smMg0CarsPvDMeD2+vPGCtl6sA/eyMqXIC/cLRlziq1wi2D2dXw/XCrnOaYYLQB3Awfp7PJqqg6SoqjXNJQXzdhB5HwFHCQi50+sY37MAmCL1cyDxr25famUFpIp7BUsrK3hQrkSEh6WCwW30U+wJI8l8hICIk5KruIkKFmoedxGdyt5qSRDoyDh5UFnUIjW+MLpWAasAguOZI+A7EktBcOcentMc63oJZy9O/Z0fvHESETMqMa39Fl7DEMPsJxPTUc0ixWjtFiDjNySs0rbmzNOUMHFJLxOXMqb/5pNZ/YptagNmn9D6Jd/D6QpJhaThOhTWNc9MSmZlBF1DxqDZDHqFNJBJeqKHAKe+39AQ=="
   :shearx "dVLbTsJAEJ3T0pZLW25GkGu5BlDBGN4Ff0B/AeMlJGINAeMT8UG/gp8g8Sf8EZ410XedWR+QJm5yzuycOdPdTLe/8lZev3vc7b8CuXIb9GelieJADM0aWrcwiHQy4iCQpuvo9saT0c3Vp8tKhXronr4RFc/BXzgMmHNKrXLLaH45fj8IlDMcE4wOgOvx42w+XZdVh6c4yiUNjU0TUoiEZ4CNvHvyxDbuw9T3vxzJStCwJ7c3hDJCOoWlkpN9TKhItoRkCcvl8rvMB5iS5xNZCZaYo7KLCIXULPQCLvy79YJTHZj4D2sTOlm3MY0vlIYRgklwyJb0GXBT0F44xKV3wDgLQC3lGN6zY/CPI0IGZEZ1vqPD2GWE+AjbKanhkGaycoQ2c5iRV2pBcTMw5yztkyXjsxdU2f7Taj7uttakDmnDD6Id/D6QpJjadgvWtjEuemJbC1FV1ALqTVCJ0SBPBIdqKHKK2Mb7Aw=="
   :sheary "dVJLTgJBEK03HwaYDyBGkM/wJ4A6GMNe8AJ6BYyfkIAYAsYVcaGn4BIkXsKLsNZE91rVLhASO3mvul69mu7UdGdZXBY7wUnQeQUyhSboz0oSxQAb9QoaQ5hEOpkxEEjTdQTtwah/e/3psVKiNoKzN6L8BfgLR1vmjFLL3NKfXQ3eD7fKKY5xRgvAzeBxOpusCqqjqDjKJQ21dRN2EAlPAQdZ7/SJbdyHyXj85UrmQ8O+3N4USgnpFJZKRva2UJ4cCQkfi8Xiu8AHhCTPxtMSLDFHZRcRMtQs9Bwux3erOac6MBo/rELQyRraGl8oCdNAiOCSI+kz4CWhvXCISW+Xcb4FtZSjd8+O7j+OCJmQGVX5ji5jj2HwEY7rq+GQFmLlGE3mMCOr1Jzi+tac03RAlozPmVNp80+r+XibWp1apPU+iHbx+0ASYmo6DVibxpjo8U3NoLKoOVTrIJ9Ro6IILlWQ5xT22vsD"
   :split-all "rVNLbhNBEH2vx+Ox4/k5jpzM2GM7PysO4CDkPYQLwBXC35IToyhBrCwWcApfIhKX4CB4DRLsoaotCGMpmQ0tVVXX66pX1TPVo6veVW80fDAcfSbT7oD4ZzWAiKyxv8uDCV3AgRsRhHEcDo/GpyevX/4IBdnGEYePvwDZUwrDvZXg1KI7knJy+WL87e7K8abYWOSQ5Kvx+4vL80XXZvSsXpMjw/3rJK6zWrmg8dkKH36QMMnj+XT6M1CvQ8NEu3dVbapyUNGTVPc1VRl8NfUO5/P5r64UKKvfirfUeBq8pruqqpL9Fk6bz6Znixm8v2Vj8vmb8eTrjWWD/1F2Yyb9O+Tp9N2iTAfepGYimgbdEstgAF9c5yMZhTSfxMSap1xPkBe7bMTxW+ARbohQqvqSqlFI1SikWl9SNQupmrdS/blvaO9rhDmJLHOSFjEnaVGTSbykygqpslupqnCpE70nvzYQaYqUpE0/8MOOHWaYsmD3ORBdEWlZtG11f+VdbOEOPB13f4bt/Mu0gxXmsT4OYY6/AxtcPui6Bg38A3r5wEjxOI+VsKNom3t9oiOyj54CAXaZicvadexv"
   :slot-color "XVK5ThtRFD1nFo/t2TwEBfAyHttgYZuYKLhFiHxBKnoQiywZLLFIVBb5DjepoYil/ER+xHVS0Dv3PgpiP+lu593tnZnBLJtlg/6X/uAXWa53iP/OByAmfbZb3B3RBWy4MUFYts3+/vD69Ori7mckUAP77H/9DdS+UVp8es9myMSmk3Ihx025Njh5jGhbrERHT0BGObfj8WuoUUqLWzrZVbWhykZeb8rq+6pqCNQkKafT6aIuw3MaV/xNNZ4mF9UrqHLMO+wqz8Y38wmKqb5vETCP+PD5+cVPmTQPFp4ucTcavxZ6TGL4xz+kXBsFx3+B6G13M8V0DvndU2sWc+BKsVIxz9BCg/BKoxzskBNDVNkQ0hS6Th/Oh3/2VnjcEFsS6UqTy+Hj/cPtvG4qMqOLcmVxZ6VoW2yo1HofxXPEC/KpyYeVk6hX+MyO3ORFKgavGt1e6bOJHjzxJ2gsf3jzsmgZa6MLS/lY59v/kmhSJ9ilt5wYK15axhw0Fa1yu02kIjvIFAjRYk1C+u+5/wA="
   :animation-fields "lVO7ThtBFD1nH37gtY0NBGMb2xiwMASDEvfeidKHIj+AlARZAjkigKgQBZFS5ANSRfxBkGgo0qTLZ9BQEQmKlCly72xB1sJSvNKZ0Zx77j0zs3e6F42LRrfzrNO9JMv1NvHPNwXkyQxbi1zZpQ+48PME4bguOxv9ve2dtx++5YRawAY7L34C81uUEutD6rJlm5Kzffimf/d0KDwr86RgleS7/vHB4f5N3WY07DghIYfLD0ksMp06kIhL7g2ObhJ0kdzNOBKZou8xAbKS611LAanI/cHgd7Z3DtToYM78AXw93az5pfVSvVOgrETmx1c5AALzHSjUWGg+f1kX64TGKsWSTkkVT5jPQNq8Bjx7TW6V/dsTIJANeGdkFnQ+ypTXYCi7eIU47GcV5j1grkYotBSjUkVdm03B1uPikRZFa/FJ8GWMTLV2IusZXevt9cIxrWfUOtTsEaqR1m5kba88lArh+ZjWJWt9Hd3Zf2em4VP7cUn+elbwROBJUwW5mm1EOAlhNtmWMSWoWLZqx9ZQT5ewhqS2anCChfizsh2Xi3MtrMIx98A0o9dYUFE7WGEyLswrPxnnPDSVrXKpRdQEy2gokcUi52XJzIP2Lw=="
   :chain3 "nVLLTsJQEJ3T8hJaCsEI8ijl1QBqMYa9wS/wFyCikqAYhMQV8T/Y+AeS+BP+hgvWunCvM9dEbRM2NJkzc888zr29t7tyVk7XO/G6L0C+3AL9+zJEFpCAW0NzjDCRTmELBNJ0HV5ndNO/Gt4/J5mqUAfe2StR6Rw84ihQnVdslXv684vR+2EgnWWfYmsDuBw9zObTdVl1OArjnNLQCDTV2Ztse2whC7qBQvL0kXt4CKaTyacpKxsa9uUsYYGsgE4xyeQlTgiUyBCXtrFcLr/KPC8i60ImJy4qxXGJdgRC6s/oRQwmt+sF/apmgP747vpto6qxhWrPCahm/YqD4WyzoPlPsPe05TFZ0FaXQFqEO47RYoyxFRRbVOgGriZHBxTleEEV/4NSO0r6OZfapPU+iHbx8w7TUtQymoj6Cy3hU34uRFVhi6i7IJutQY4QJtVQ4iUSf7Xf"
   :siblings3 "nVLLTsJQEJ3T8hIKhWAEeZTyagC1GMPe4Bf4CxBRSdAahMQV8T/Y+AeS+BP+hgvWunCvM9dEbRMTQ5M5M/fM49zbe3sre2X33CO39wQUKm3Qry9LZAIJOHW0JggT6RQ2QSBN1+F2x1eDi9HtY4qpKnXhnjwTlU/BIw4C1QXF1rhnMD8bv+4H0jn2abYOgPPx3Ww+XVdUh60wzikNzUBTg32SbYctZEI3UEwd33MPD8HU896TsrKgYVfOEhbICegUk0xB4oRAmQxxGQvL5fKjwvMisi5m8+KiUhyXaEsgpP6MXsLQu14v6Fs1CwwmN5cvf6oaG6j27YBqzq84HM3+J9h/2PCYLGipSyAtwh2HaDPG2IqKLSl0AleTpz2Kcrygqv9BqR2l/JxDHdL6b0Tb+HqHGSlqGy1E/YWm8Gk/F6KasCU0HJDF1iRbiCTVUeYlEj+1nw=="
   :multi-minimal "tVPbTsJAEJ3ZcpNbaTCCBUq5NYBSjOHd4Bf4C6ioJGoThOgT8T948Q8k8Sf8EHnWRN91Zo3SNt4Tm8zM7pmZPdOz2c7MnJkde9Pu3AjUiw0E15cGUFHE0Kpg/RiDAAoEVQQEoShotwcnvcP+2XWSoBK00d6+BSjsIB3R8lXrEi1TT2+8P7hf96UzFFNkTUQ8GFyMxsN5UXaY0kcpJbDma6pSTJCtkAVUxDjmkluX1EOH4NBxnhK8M1DgKv9LkF2GnQIRzui8jrErQJyDZuB0On0u0nkh3ufSWQ5hLo7yaoldQCqj5HHXOZ1PwJATgghR1wY2yEfIchLNS2/55s7CGoRpPYGPhNV+Jaz2tbCaW9jRudOimfuP30qr+aQV79Km/19Z7U1ZN+ne0eD47lNW3cXavfoZa9ck1u7DgrX9h7vUFndZ8r4cOVHSi1nQBMGUy/j64DQuasTrGPYWqoynvFgAyozmsWohGGQ1MBlIQAULtMXYovYF"
   :region1 "XVJJTipRFD2nGgqoDqKxoSkKUSKiaAxTY3QFbkHzv4YEJcFv8kdE18HEsQ40cRNuhLEOnOO9j4HCS2533u3eqeq+pq9pt3PY6b6RpVqL+HWWgJj02Wxwu08XsOHGBGHZNjv7vevzq7+3L5FAdeyzc/oOVM8oLfZ+shmyaNNJOJXjzkxE22I5Or4HUsoZDgZfoUYJLa7rZFfVqiobWb0pqe+rqiJQU0w4Ho+nNRme0bjsr6nxNDmvXk6VY95hV3gxuJmMkE/0fdMAWcRHT0/P/iz2dInb/uAr12Yxhn/yKOXaKDj5BKLZ0maK6RzywVNrFnPgSrFSMUnRQJ3wCv0M7JAjQ1TJELIhdJ3f/el97C7wuCq2ILIjTS57///dDSc1U5EanZcri1sLRZtiQ6XWWxHPES/IJiYfVkaidu6ALbnJipQNXjG6udBnDW144o9Qn//w5mXRPNbEDizlY5mz/6WoSa1gm958Yqx4YR5zsKFohZtNIhHZQqpAiAarEtL/yf0G"
   :region2 "fVLJbhNBEH1vFs94VjssCV7G4zix4hgchHyNUPwF/EIQiyw5GGWROFnkO3zJOTkQwU/wIz7DIXdT1QaCrZCWuvaqftVV/ev8Ou/3XvT638hKo0P8cx4AKRmy3eLOiC5gw00JwrJt9vaGR4fv3558ScTUxB57g+9A/RWlxLPbaCtm2aaTcS7HXbCEtsVq8vIzkFPO8Xh8E6uW0eITfdlVsq7Ehq+eisqhkjoiZeWM0+l03pDHC6pXww1lngYHKhWVOKYPu8bX4w+zCYJM+5tH8JHuX15ehQvdUxAno/FNsctyivDgQtK1UHTwE0gWoM0rpnLMc0+5AebAlWT9ilmOFpqEVxoVYMec4K7WA8v/H4o1A+L0628Ug/wPisHHu1A8ug9E8BeETqtiprIpMzs8ezP88XRlmOvCS3J3pci74afTs+NZw2Tkhgbisri9krQlPNb5enHwWGRHViXyM5MBqyCebrEbPmdHfL7cqvHUDG2v1NpAF57IEzSXN9B0lyzb2tiFpYN5yMXiljWoE+3QWw5M1V5atjnYVGuNW20ik7uNXA0xWqyLyvA29hc="
   :region-fields "XVLNbhJRFP6+GYYB5o+JxlZ+hgFaUopCo2xNHeMD9BUwoiGhYqBNXBHfg43rurCJGx/BF2niThcs3OE5l0WFm5y/73znnHtP7vA2vU2H/Wf94Xey0ugS/50HQER67LR5MqUD2HAigrBsm/3B5HL0frz4FgrUxID9Vz+B+gWlxdN7NgPGNnMJN3KcrQlpW6yG55+BlHLms9k60Cihxcc62VF1oMpGQTMV9T1Vdfhq4oSr1WrTkOF5javeoRpXySX1iqpy5h12jW9mH+6WKCX6vo2PAqIXNzdfvW3s6iUW09m62GMcwct+Sfn5S8DP/gJhwrj1/HU+O5PO2Q8g4NX409X1fLwYfBzN1252AbnkF53mSCPFUrTRJNzyNA874NLsrGJ205LNja7fTn4/2VvpgdiyyKn0eDfRAXcNU5EaXZKUxeO9oiOxgW7ZfSReTjy/kBg+rLxEveIZu5IpiFQNXjO6s9fnED244i/R3P0DZvvhLtbBKazsD/CQ268TK6nrn9DdJUaKl3exHFqK1njUIRKRY6QKBGizLiG9e+4/"
   :skin2 "bVJLbhNBEK03H8+M5+chiAR/xmM7seLY2Ah5i1B8AbiCkQ2ymMQoIctY4RyWEOtkkQgukYMka1hkjanqCYonoqWuz+vq1/VKPbxKrpJh/1V/+BMo1zugtbVBFAIu2i3spjCJdDJDEEjTdfQHs4Pxx+nxZcBQgwboj66Jau/AFC8equEj0mHEWPEyMxdA11AJ3pwRJeB1NJ/f+ZLF0PBcXjbFbIrRyZaTssSumBp54qIYy+VyVefHC5JX3C1xlhQXJXLEGEqHXsX7+eHtgoqx6Ft5ZFP4+vz8ws1yS5o4Tud3Tg/2KV8yORd1twm1qAGySmmBdB8Lpb2sNDZ5AuOTyexX79FoNtmXeO8J6afZ4Y+6qk+ULfKBhp1HV7bZ+zIr6xlHBkeeHat60goh9C6ikNz976xK9Hn7v4mCbJZKvBLsZy1bEquZZTom0w/jk/TLIKen52SUo+Qf5ejzfym/rtE5Qnc0ndxTwbGkfUZu7Bh/vr1dGeBmWZfGArqRufYJmk9E5uL+9ZfocJ2t8IqSWVW2nW5sUZeEdkGN/F9ULQR5rE17pMksniL7wpEUdbxdWPnCUPBSHjOoKWgV221QzHuHEgF8aqHGKdyH2r8="
   :rotate1 "XVFJTkJBEH3vfz7jZzaCzHMAFYxh73ABvQLGISQgCYJxRVzoKTiEJF7CY7hhrQv3WvVZ4KeTetX1XlV1V3dvWVqWep3jTu+dzBRbxL+VBKJkiI0qm0NagAkrShCGabLTHYz6dzcPbxGhyuiyc/4B5C8pLQ63sjMOW5Ga/ux68HWwJafEx8TaJG8HT9PZZFV0KkoOBkUyWN8UMcGAfyqKSY7GjysvTfiGIUOUJC0PvSCzkZNnaSAdORmPf8IaFWhwTwezFFIKJvyqZHQfUsjDVhcvcLFY/BblaK/G2URanU+Tg7oLKHicZzJzvBrfr+aArVd4IcNygVdxUZVPP4ELuA3rFhZ1/JocEhbbFfNIAztScOaG4RXmiC1Bv1jWYXMONraeMI19+PRl7DnK7l90Boy4uQbaMM6+gR2uPz+uSS27SZ87Map8zM15UFE2x1qDKIjVUVIijCrzEjK0yf0D"
   :rotate2 "dVLJTgJBEH1vFrYZQNEoso4LRFxGY7gr/oD+gsYlJCJGwXgiHvQr+AhJvPgJfoYXznrwrlXtQYfETupV16tX1T3V0xwFo6AZ7oTNZ7JQbRB/1gyQJT3Wl7l6QRew4WYJwrJthlvtztH56c1TRqhFbDHcfwXKh5QWmxPqgmGXpOaof9J+35hIz4ufElsjeda+6/Wvx1VTERhMScpi7beIOSYTPcnYZKd7O47RRvzCsyQzQ9dhDGQxs3svDaQjr7vdz7RGFVpc0A9zFeYVbCQ0U9C9p1CGr266wuFw+FWVo2MaF3N5dXEVp3SXVHDMmOwSj7uX4wHg6xUeyDRoPYrLanrvDThA1MwyitYV0Hr5R5GESx3QilwjLTYn5sgRfqZiJgMrJsw2G4IJsaJhSwbrE0POYx1xnZ0/wGL0nc0IMlGujjVYrQ9glj+/x7SKGv4q41FhVvmpKOdgSdkSV+pERayGQIk0llmWkN6v9hs="
   :rotate-bezier "XVJJTgJRFKzqpgGhmcSIMs8RVDSGveAF9AoYiSEBMYouXBAXegoOIYkbj+Ax3LjWGPf63scEoZOq93+9+kNXd3Oan+abjYNG85lM5mrEvycORMggqyVu9ekANpwIQVi2zcZeb9A5714/hUUqYI+No1cgc0LZYnfJnTRqUdZ0bs56HztL7YTUqKBO8mo46oy6u6fdu173K2fW5Q0HxGCxMl/KVa74R9KxycHw9t1LG75+0JJOnI6HXpCp8OG9bECz8fA7pLMsLW7q6zlKCSUbfu0kdRxUysDVEstyMpn85ORor85TqxtafGoO6GhFyWPCstM8HV68jwFXr/BAhkDrUUpE26034BgztFpA+y9jGkf7UpQXgXamcydmhzjUmMpyjZBgXeCRI9xw1iQDyyvKPmvCfkHKqGnD1aWoN7ANn2bnjlFY/NomgvCiVkUdVvsTWOPsJ4mpqeZu0bdojKgeXdQ8KKqaZrlKZAUV5FUIocSMTBmce38B"
   :rotate-stepped "bVJLTgJBEH1vhuE7M/iLIJ/hoxJRQWPYK15Ar4CRGBN1iKBL40JPwSE0ceMRPIYLXevCvVa1CxxiJ/Wq+9Wrqp7q6TxWH6ud9k6780QWKk3iz5oHsmSGjWWundEBbDhZgrBsm+2t0/PeSX/44AtVwxbb+y9A+ZBSojWlLhi2Ljm9q+PTj82pcE78jNg6yctw1Bv1W8NRfzDov1VMYtVgWhQWVye5nGMqOZKITZ6H1+9x2kicZSyJzNOJMQ6y6O/eSgGayuGXp6eAFpf0+xyFnIKNpEYKus8olOGqmw04Ho+/K9I6rufiXF5dQsVp3aUUYmZadolH4cX7DeDqFe5ID7TuxWU1vPcKHCBqun4V3QHQff5fIU0c6pxW5Bqe2KJYTFq4fmAmAysuzDabgkmxomFLBhtTs85jAwmdnXuDWvS5zQj8KNfAOqzuJ7DA379kVkVNd42JqDCr/EyUi6GubIkrDSIQW0VVCQ/LLMuRmYn2Bw=="
   :translate2 "lVJJTgJREH2vB6ZuQMU4MDaKRFAbYtgbvIBeQeMQEhXjFFfEhSYewYRDSOIlvAhrXbjXqu9Cm7ixk/fq16uhq6t/ZxSMgk64GXZeyHylQfx6ckCW9Fhf5uoJXcCGmyUIy7YZtnqne8eHl88ZkapoMdx+BUq7lBYbE9l5oy5Jzd71Qe9tfSI8L3ZK0CR51Lu9ur4YV0xFYDglIYsrP0WcYTJxJRGbPO3fjGO0ET/xLInk6DqMgSxktu6kgXTkRb//kVavTIuL+mGu0rySjYRG8nr2lErw1UyXORwOPyvy6pj6hZkFNXFNTukpqeSYNdlF7vfPxgPA1xHuyTRpPYjJarjbFuwCO4gC+FuDqeyeS9Wj4OkflUm41IXWZOy0YE7gyEh+pmw2CSsmSpsN4YSgYNSi4frET1nAGuK6a3+AavRemJVlolodTVjdd2CW39dpWpMa/irj0cSs6lNRzcGSqkXW6kRZsIJAhTSWWRKX3k/uFw=="
   :scale2 "dVJJTgJREH2vm2aQBgSMKGM70AHUxhj2oBfQK2gcQqJinOLKuNBTsPEGkngJL8JaF+616rvAJtrJe/Xr1dDV1b8z8kZeJ9gKOq9ksdYkfj15IEMm6a+wcUoHsOFkCMKybQbt/tn+ydHVS1qkJbQZ7LwBlT1Ki42p7KJRl6Vm/+aw/74+FS6InRW0SB73765vLsc1U+EZnpGQxfqkiDkm4tcSscmzwe04Shux06QlkTydCKMgS+nugzSQjrwcDD5T6lVpcVE/zFEqKNmIa6So56RSBa6abJXD4fCrJq+Oql/KLaiJafKMnhJKEbMmu8yDwfn4HnB1hEcyZdF6EpPRcPdZ0AN2EQbwtwZTuX0B9LT6n6w/KxNwqAtdlbFTgnlBREZy01WzSVhRUTbZFI4LSkYtG/anfsoC1hDTXbv3WArfC7OydFjz0YK1/QHM8ec6ZTWp6TYYCydmVJ8NaxEsq1rmqk9UBXV4KqSwwoq4TE5yvwE="
   :shear2 "lVJLTgJREKyaYfgOoGAE+QtKABWIYS94Ab2Cxk9IUAyKcWVc6CnYeANJvITHcMNaF+61+7nQIW6cpKpfV3+mp+d1pqVpqdPcbnaeyUyxTvx6kkCcjLBaYW1AB7DhxAnCsm02W/2zg9Pjy6eYSKtosbn7AuT3KS225rIzRi1LzcH4qP+2ORdOiV0QNEie9G+uxqNZ0VSUDIclZHH9p4gJhoJXErHJs+H1zE8bgUHEkkiSjo9+kNnYzp00kI4cDYcfUfUKtLiiH+YopZRsBDWS0XNEKQ9XzWKBk8nksyiv9qufTaTVBDQ5rKeQks+syc7xcHg+uwVcHeGejNq0HsTENdztCh6BPXgB/K3BVPYupOoV6LX/URmCQ13omowdFSwLfDKSGyuYTcLyi9JmXTgoyBo1Z7g691PS2EBAd+3eYtV7L8zKYl6tigas3juwxO/rtKhJdbfGgDcxrvqCV/OhrGqOa1WiIFhHSYUoKsyLy8hP7hc="})

(defn- graph-fixture [name]
  (native/decode-graph
    (native/decode (.decode (Base64/getDecoder) ^String (get graph-fixtures name)))))

;; A full sequential decoder and writer preserve every byte of the known graph.
(deftest native-complete-graph-roundtrip-test
  (doseq [[name packed] graph-fixtures]
    (testing (str name)
      (let [project (native/decode (.decode (Base64/getDecoder) ^String packed))
            graph (native/decode-graph project)
            rewritten (native/decode (native/encode-graph graph))]
        (is (:complete graph))
        (is (Arrays/equals ^bytes (::native/payload project) ^bytes (::native/payload rewritten)))))))

;; Model references stay distinct from ordinary values, including repeated names.
(deftest native-model-reference-graph-test
  (doseq [[name expected] [[:chain3 [nil "root" "alpha"]]
                          [:siblings3 [nil "root" "root"]]
                          [:multi-minimal [nil nil "root"]]]]
    (let [graph (graph-fixture name)
          bones (filterv #(= :bone (:kind %)) (:models graph))]
      (is (= expected
             (mapv (fn [bone]
                     (when-let [parent (native/graph-node graph (get-in bone [:data 3]))]
                       (:data (native/graph-node graph (get-in parent [:data 1])))))
                   bones)))
      (is (= (if (= :multi-minimal name) 2 1) (:skeleton-count graph))))))

;; Native Bezier handles span adjacent keys and time is stored in frames.
(deftest native-transform-key-graph-test
  (doseq [[name property values] [[:rotate2 0 [7.0]] [:translate2 1 [11.0 13.0]]
                                 [:scale2 2 [1.25 0.75]] [:shear2 3 [3.0 5.0]]]]
    (let [graph (graph-fixture name)
          timeline (first (filterv #(= :timeline (:kind %)) (:models graph)))
          keys (mapv #(native/graph-node graph %)
                     (:data (native/graph-node graph (get-in timeline [:data :keys]))))]
      (is (= property (get-in timeline [:data :property])))
      (is (= [0.0 15.0] (mapv #(get-in % [:data :time]) keys)))
      (is (= values (get-in keys [0 :data :values])))))
  (let [graph (graph-fixture :rotate-bezier)
        keys (filterv #(= :keyframe (:kind %)) (:models graph))]
    (is (= [3.0 8.0] (subvec (get-in keys [0 :data :curves 0]) 2 4)))
    (is (= [12.0 22.0] (subvec (get-in keys [1 :data :curves 0]) 0 2)))
    (is (= 1 (get-in keys [0 :data :flag]))))
  (is (= 2 (get-in (first (filterv #(= :keyframe (:kind %))
                                  (:models (graph-fixture :rotate-stepped))))
                  [:data :flag]))))

;; Structural writes rebuild references when a new native keyframe is inserted.
(deftest native-graph-structural-write-test
  (let [graph (graph-fixture :rotate2)
        timeline-index (first (keep-indexed (fn [index node] (when (= :timeline (:kind node)) index)) (:models graph)))
        keys-reference (get-in graph [:models timeline-index :data :keys])
        keys (get-in graph [(:space keys-reference) (:id keys-reference) :data])
        last-key (native/graph-node graph (peek keys))
        key-index (count (:models graph))
        new-key (-> last-key (assoc-in [:data :time] 30.0) (assoc-in [:data :values] [42.0]))
        updated (-> graph
                    (update :models conj new-key)
                    (update-in [(:space keys-reference) (:id keys-reference) :data]
                               conj {:space :models :id key-index}))
        reopened (native/decode-graph (native/decode (native/encode-graph updated)))
        reopened-keys (filterv #(= :keyframe (:kind %)) (:models reopened))]
    (is (= [0.0 15.0 30.0] (mapv #(get-in % [:data :time]) reopened-keys)))
    (is (= [42.0] (get-in (peek reopened-keys) [:data :values])))
    (is (= 2 (count (filterv #(= :keyframe (:kind %)) (:models graph)))))))

;; Unknown structural data must not be silently dropped by the whole-graph writer.
(deftest native-graph-write-boundaries-test
  (let [graph (graph-fixture :region1)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (native/encode-graph (assoc-in graph [:models 0 :data 999] 0))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (native/encode-graph (assoc-in graph [:models 0 :data 8] Double/NaN))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (native/encode-graph (assoc-in graph [:models 0 :kind] :unknown))))))

;; Differential native fields reproduce region transforms, paths, colors and skins.
(deftest native-region-and-skin-interchange-test
  (let [source (native/to-spine-json (graph-fixture :region-fields) 0)
        region (get-in source ["skins" 0 "attachments" "slot" "part"])]
    (is (= {"x" 11.0 "y" 13.0 "rotation" 23.0 "width" 29.0 "height" 31.0
            "scaleX" 1.25 "scaleY" 0.75 "color" "11223344" "path" "textures/part"}
           (select-keys region ["x" "y" "rotation" "width" "height" "scaleX" "scaleY" "color" "path"])))
    (is (= "part" (get-in source ["slots" 0 "attachment"]))))
  (let [source (native/to-spine-json (graph-fixture :skin2) 0)]
    (is (= ["default" "red"] (mapv #(get % "name") (get source "skins"))))
    (is (= "red/image" (get-in source ["skins" 1 "attachments" "slot" "image" "path"]))))
  (let [slot (get-in (native/to-spine-json (graph-fixture :slot-color) 0) ["slots" 0])]
    (is (= "12345678" (get slot "color")))
    (is (= "112233" (get slot "dark")))))

;; Native setup poses do not get baked into the relative animation key values.
(deftest native-animation-interchange-test
  (let [source (native/to-spine-json (graph-fixture :animation-fields) 0)
        bone (get-in source ["bones" 0])
        tracks (get-in source ["animations" "move" "bones" "root"])]
    (is (= 23.5 (get bone "rotation")))
    (is (= [7.0 23.0] (mapv #(get % "value") (get tracks "rotate"))))
    (is (= [1.25 2.0] (mapv #(get % "x") (get tracks "scale"))))
    (is (= [0.0 0.5] (mapv #(get % "time") (get tracks "translate")))))
  (is (= [0.1 8.0 0.4 22.0]
         (get-in (native/to-spine-json (graph-fixture :rotate-bezier) 0)
                 ["animations" "move" "bones" "root" "rotate" 0 "curve"])))
  (is (= "stepped"
         (get-in (native/to-spine-json (graph-fixture :rotate-stepped) 0)
                 ["animations" "move" "bones" "root" "rotate" 0 "curve"]))))

;; Every supported fixture produces a document accepted by the real editor model.
(deftest native-editor-import-closure-test
  (doseq [[name _] graph-fixtures]
    (testing (str name)
      (let [graph (graph-fixture name)]
        (doseq [index (range (:skeleton-count graph))]
          (let [document (data/import-spine-json (json/write-str (native/to-spine-json graph index)))]
            (is (= "defold.skeleton" (:schema document)))
            (is (pos? (count (:bones document))))))))))

;; Independent scalar channels retain their exact native codes and split flags.
(deftest native-scalar-timelines-test
  (doseq [[name code flag] [[:translatex 14 1] [:translatey 15 1]
                           [:scalex 16 2] [:scaley 17 2] [:shearx 18 4] [:sheary 19 4]]]
    (let [graph (graph-fixture name)
          timeline (first (filterv #(= :timeline (:kind %)) (:models graph)))
          split (first (filterv #(= :empty-116 (:kind %)) (:values graph)))]
      (is (= code (get-in timeline [:data :property])))
      (is (= flag (second (first (:data split)))))
      (is (= [2.0 3.0] (mapv #(get % "value")
                             (get-in (native/to-spine-json graph 0) ["animations" "move" "bones" "root" (clojure.core/name name)]))))))
  (let [graph (graph-fixture :split-all)
        split (first (filterv #(= :empty-116 (:kind %)) (:values graph)))]
    (is (= #{7 3} (set (mapv second (:data split)))))))

;; Every supported fixture survives a real editor import, export and native save.
(deftest native-editable-save-closure-test
  (doseq [[name _] graph-fixtures]
    (testing (str name)
      (let [graph (graph-fixture name)]
        (doseq [index (range (:skeleton-count graph))]
          (let [source (native/to-spine-json graph index)
                direct (native/update-skeleton graph index source)
                document (data/import-spine-json (json/write-str source))
                exported (json/read-str (data/export-spine-json document))
                updated (native/update-skeleton graph index exported)
                reopened (data/import-spine-json (json/write-str (native/to-spine-json updated index)))]
            (is (= source (native/to-spine-json direct index)))
            (is (= (:bones document) (:bones reopened)))
            (is (= (mapv :id (:slots document)) (mapv :id (:slots reopened))))))))))

;; Structural edits rename/reparent bones, add regions/skins, then delete them.
(deftest native-source-structural-edit-test
  (let [original (graph-fixture :region1)
        source (native/to-spine-json original 0)
        edited (-> source
                   (assoc "bones" [{"name" "新的根" "x" 7.5}
                                     {"name" "child" "parent" "新的根" "rotation" 25.0}])
                   (assoc-in ["slots" 0 "bone"] "child")
                   (assoc-in ["skins" 0 "attachments" "slot" "second"] {"type" "region" "width" 12.0 "height" 18.0})
                   (assoc-in ["slots" 0 "attachment"] "second")
                   (update "skins" conj {"name" "alternate" "attachments" {"slot" {"image" {"type" "region" "name" "alternate/image" "width" 30.0 "height" 40.0}}}})
                   (assoc "animations" {"walk" {"bones" {"child" {"translatex" [{"value" 2.0 "curve" [0.1 3.0 0.4 9.0]}
                                                                                       {"time" 0.5 "value" 10.0}
                                                                                       {"time" 1.0 "value" 20.0}]}}}}))
        updated (native/update-skeleton original 0 edited)
        readback (native/to-spine-json updated 0)
        deleted (-> readback
                    (update "bones" subvec 0 1)
                    (assoc-in ["slots" 0 "bone"] "新的根")
                    (assoc-in ["skins" 0 "attachments" "slot"] {"second" (get-in readback ["skins" 0 "attachments" "slot" "second"])})
                    (update "skins" subvec 0 1)
                    (assoc "animations" {}))
        final (native/to-spine-json (native/update-skeleton updated 0 deleted) 0)]
    (is (= ["新的根" "child"] (mapv #(get % "name") (get readback "bones"))))
    (is (= "新的根" (get-in readback ["bones" 1 "parent"])))
    (is (= 25.0 (get-in readback ["bones" 1 "rotation"])))
    (is (= "second" (get-in readback ["slots" 0 "attachment"])))
    (is (= [0.1 3.0 0.4 9.0] (get-in readback ["animations" "walk" "bones" "child" "translatex" 0 "curve"])))
    (is (= 3 (count (get-in readback ["animations" "walk" "bones" "child" "translatex"]))))
    (is (= ["新的根"] (mapv #(get % "name") (get final "bones"))))
    (is (= ["default"] (mapv #(get % "name") (get final "skins"))))
    (is (= #{"second"} (set (coll/keys (get-in final ["skins" 0 "attachments" "slot"])))))
    (is (= {} (get final "animations")))
    (is (= "root" (get-in (native/to-spine-json original 0) ["bones" 0 "name"])))))

;; Updating one skeleton cannot mutate shared strings, colors, or its peers.
(deftest native-update-preserves-peer-skeletons-test
  (let [graph (graph-fixture :multi-minimal)
        before (native/to-spine-json graph 1)
        source (native/to-spine-json graph 0)
        edited (-> source (assoc-in ["bones" 0 "name"] "changed-root")
                   (assoc-in ["bones" 0 "color"] "12345678")
                   (assoc-in ["bones" 0 "inherit"] "noScale"))
        updated (native/update-skeleton graph 0 edited)]
    (is (= before (native/to-spine-json updated 1)))
    (is (= (mapv :name (native/graph-skeletons graph)) (mapv :name (native/graph-skeletons updated))))
    (is (= "changed-root" (get-in (native/to-spine-json updated 0) ["bones" 0 "name"])))
    (is (= "noScale" (get-in (native/to-spine-json updated 0) ["bones" 0 "inherit"])))
    (is (= 2 (:skeleton-count updated)))))

;; Unsupported edits fail explicitly instead of saving an unchanged old graph.
(deftest native-update-rejects-unrepresentable-data-test
  (let [graph (graph-fixture :region1)
        source (native/to-spine-json graph 0)]
    (doseq [edited [(assoc source "ik" [{"name" "unsupported"}])
                    (assoc-in source ["skeleton" "fps"] 60)
                    (assoc-in source ["bones" 0 "parent"] "missing")
                    (assoc-in source ["bones" 0 "x"] Double/NaN)
                    (assoc-in source ["skins" 0 "attachments" "slot" "image" "type"] "mesh")
                    (assoc-in source ["animations" "bad" "events"] [{"time" 1.0}])
                    (assoc-in source ["animations" "bad" "bones" "root" "rotate"] [{"time" -0.1 "value" 1.0}])
                    (assoc-in source ["animations" "bad" "bones" "root" "rotate"] [{"curve" [0.1 0.0 0.2 1.0]}])
                    (assoc-in source ["animations" "bad" "bones" "root"] {"translate" [] "translatex" []})]]
      (is (thrown? clojure.lang.ExceptionInfo (native/update-skeleton graph 0 edited))))))

;; New projects use independently constructed schema defaults with isolated names.
(deftest native-create-without-template-test
  (let [first-source (native/to-spine-json (graph-fixture :region-fields) 0)
        second-source (native/to-spine-json (graph-fixture :split-all) 0)
        graph (native/from-skeletons [{:name "制作工程" :source first-source}
                                     {:name "second" :source second-source}])
        reopened (native/decode-graph (native/decode (native/encode-graph graph)))]
    (is (= ["制作工程" "second"] (mapv :name (native/graph-skeletons reopened))))
    (is (= first-source (native/to-spine-json reopened 0)))
    (is (= second-source (native/to-spine-json reopened 1)))
    (is (= 2 (:skeleton-count reopened)))))

;; Invalid project sets and conflicting global frame rates cannot be serialized.
(deftest native-create-boundaries-test
  (let [source {"bones" [{"name" "root"}]}]
    (doseq [entries [[] [{:name "" :source source}]
                     [{:name "same" :source source} {:name "same" :source source}]
                     [{:name "a" :source source} {:name "b" :source (assoc source "skeleton" {"fps" 60})}]
                     [{:name "a" :source (assoc source "skeleton" {"fps" Double/NaN})}]]]
      (is (thrown? clojure.lang.ExceptionInfo (native/from-skeletons entries))))))

;; The editor always emits default referenceScale even without physics objects.
(deftest native-default-reference-scale-test
  (let [source {"skeleton" {"referenceScale" 100.0} "bones" [{"name" "root"}]}
        graph (native/from-skeletons [{:name "default-scale" :source source}])]
    (is (= "root" (get-in (native/to-spine-json graph 0) ["bones" 0 "name"])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (native/from-skeletons [{:name "invalid-scale" :source (assoc-in source ["skeleton" "referenceScale"] -1.0)}])))))

;; Save As can relocate all assets without rebuilding an unselected skeleton.
(deftest native-directory-relocation-only-test
  (let [graph (graph-fixture :multi-minimal)
        reference (:reference (get (native/graph-skeletons graph) 1))
        before (:data (native/graph-node graph reference))
        result (native/relocate-skeleton-directories graph 1 "/project/images/" "/project/audio/")
        after (:data (native/graph-node result reference))
        reopened (native/decode-graph (native/decode (native/encode-graph result)))]
    (is (= (dissoc before 3 34) (dissoc after 3 34)))
    (is (= (:models graph) (assoc-in (:models result) [(:id reference) :data] before)))
    (is (= (native/to-spine-json graph 0) (native/to-spine-json reopened 0)))
    (is (= "/project/images/" (get-in (native/to-spine-json reopened 1) ["skeleton" "images"])))
    (is (= "/project/audio/" (:data (native/graph-node result (get after 34)))))))

(def ^:private advanced-fixtures
  {:mesh-unweighted "nVPLbtNQEJ1z7cSOH7FNCy15OM6jUVPAQShih5DZsOUXyqtECkQqrdRVBN+RDeuyqcQXdMeHkDVIdB9mrqniBHXDleZxz5wZ3zt3PLpILpJR+igdfQNqrQGosLaIAsBFv4v9CUpEBpUCEEgZBtLh+P3h0ZvfVUbaNET67DtR8wW4woMVGT4iA2aMJa9SbqowFOrVp5+IEvB69W48+eHLNobCXflySdSOKOOa6QLH0+nVjTyyJVIT3xXVJE9MFGM+ny9bfMqy7OvurhhLyI54FVGmvq/RwMvph8Xsf0plCZfKvqxKhTNyYmnn0iObgifn51/dfG/JpT9OpleVFI0ahZLlRbAlT2oVpbjqVMXnSNg96pA/hJyCH4O1ImNLKK28w7cltfEcJqWOCmA+5n5FUJd8RhGKAGH8hbOb4exf+HIDBj+yZxsR8k7GZId8lTIpZZplV/dGlBVui7nF97w+RlawRd+kEjdIpmuRUJfa4ORJmQwfMz17NT1kHZ7Aw9PX45/3N0Zzh23IcsBF3o7PTk6PFy2dkWjtcEhhbyOpx9aXcbXusGcGUF7Fs2OdQarMkdR5iAHHbJa6xhta9zcq7dI9stifUXv9b9JDVV3H+nRAKvtFtI38J4yENPD2Ya0TA8HDdcykjn5n9PqgmGWPEgF86qLJW7gr7h8="
   :mesh-weighted "nVPLbtNQEJ1z7cSOH7FNCy15OM6jUVPAQShixyNs2PILBQpECo3UUolVBN+RTddlU4kvYMeHkDVIdB9mrhPlocKCkeZxZ87MvTMe9y6Ty6SXPkh7X4FSrQNaoS2iAHDRbmJ/iByRQbkABFKGgbQ7eH/49uh3kT116iJ99p2o+gJc4d4SDB+RATPGjCmXqSIMhXLxySeiBEyv3g2GP3w5xlC4LTfnROyIMBZIFzgZja7+iiNbIiWxXRFV8kRFMSaTyazGr8zLuezuirIE7IhVEGHqfo0KXo6Op+P/KdVPuFT/fFkqHJMTyzhnHtkUPLq4+OJmZ0uaPh2OrgopKiUKJcuLYEue1FrlVSpTEZ8jQbeoQX4X8grFnZN8ki2B1LIJ35TUynOYlDoqgPmQ5xXB/MZv1HyeaYqgHstFT4nmkNX7FpDFu66B9K+HSKYOLYghi24QQHkFzzYiZLOPYYfcfJ6UMs28q6cpwgq3Rd2YT+ZfZFKORyr7OE2oSXVw8jBPho+x3taSXssG7+zh2evBz7sby7zDOmQ+4CJvBh8/nJ1Mazoj0dLhkMLeRlKLtS8Lbt1iy5x3FesMUnmOpM59dDhmM5e1v6Jle6PSLt0hi+0x1df/P72GxXVfmw5I9X8RbSP7bSMBdbx9WOvAQPzhus+kht4MtNqgmHmPEnH41ESVj3CX2D8="
   :mesh-fields "nVNLjxJBEK6vZ2AGZoZhdtFdeQzDY8my6mAM8WYMXLx68o66qyQoht1N9kT8H1w8r5dN/AXe/CFy1kTuWNWAPLJerKSqur+u/rq6urp9E91E7fhx3P4K5MtN0IbsE/mAg0YNxwMkiAxK+CCQMgzErf773tvT3xlGKtRC3P1OVHoBZni4DoaHwIAZYs6SWLgMDIVC5tknoggsr9/1Bz88mYZQuCcnJ8QciDFWkQ4wGg5n/4wjW1byMnbElMgVF4SYTCbzMmeZlHnBORRnSXBaRikxpr6vUcSr4Yfp+H+oOhFTdT6vqbJjSodSzrlLNvlPr6+/OIu5JZc+HwxnqRjFPGW7OSI3gC37hGtTN6VAGVycXl1cjk7PWx97o1nQrRPVqUpeC5IRPwxbRca+hJdD7LVfXt0RmuJzmBSnlQ/zCdcugPrG+YpSAEjEEu7cDgtf53ZYyxIGP7hrGwEWVQ3JznJiSpkmObpKYiw3J26Pb7xK4u+pq2OWYlKCSyV9No2oRhWQlR0kyfAw1l2Y1+1W5V7sXb7p/3yw06QH7LOsJ0xy1pfCTct6R6RtmpcUjnY21dl70rjWXR6ZPpSbcu1Q7yCV5JU4/QhNXrNZCxovatvYYTqk+2TxeEyV7X+l2yuzjTXohFTnF1EOi+8YSFDTPYa1HegLnt3GTKrqV0a9AQpZjygSwKMaSjyFs479Aw=="
   :ffd-unweighted "nVTNbtNAEJ5v7dhO7Py4SdqSpI7z06gp4CAUcUPEXDjCKwQoECkQVFqJUwQIniIXzuVSiRegNx6DAzmDRO9hZkOVH+iFlWZmZ/bbycy343RPw9OwG92Mup+BUrUNWlp5oizgotXA3hAJIoMSWRBIGQaizuB5/+nBrwxHatRBdPcr0c4DcIbrCzDS8A2YAWa8EnOTgaFQztx5QxSC16Nng+H3tLgBFK7ILydEbYkyLpAucDganV+KI0dOSrJ3Re2QJ8YPMJlMZlWu0hK/7G6LsQWckl1SlKn7NSp4OHoxHf9PqjjkVPHHRarcmFKB0DnzyKHs7ZOTT+7ct6XpV8PReTJCpUQ5ueX5cOSe5FqW5VWmDN76gm5SndIdSBX8GKwVGXmBVOcMF+Vq5R5MilIqC/MW8+VDnXGNIuQDgvgTji8Px3+Hz9bC4Ef2HMPHnMmAnBy3YpFSpmm5mhtRdq4gZoP7vCgjXrLLe5MSTJBM1zSkBtXAl4cWGWmM9eyV9JDVeQL7x48HP66tjeYW2xzLPid5Mnh9dHw4reobodYpPlLYXRrRDSSdI8io4V3fgkH2sMCsIY+EyRxzN0Vx3wObFtQHNnri7q+JXnBAGhG//DdCMUKPV4+dHque7PlNe19YvjEDHI9SSeGAm2lytWn5jOxN3plZKC/pOYHuhJTFJ1HqBtp85rCUdbyidWuNlm26SrZ0WxxTbfU71/VkVmMt2icV/yQqYP734Auo7e3BXgVmJZ5bjZlU1xOIZgsUsOxSKIE0NbDDLtwF9jc="
   :ffd-weighted "nVRLb9NAEJ5v7diO7SR227QlD8fpI2oKOAhF3ICEC0f4C+UdKRBUWolTVBD8il56LpdK/AG48TM40DNI9F5m1onyUODASDuzs/vt7M43Y7fP4rO4ndxM2p+BUq0JmpJlogLgobGJnT4yRAZlCiCQMgwkrd7LvedPf+d5pU4tJPe+EVUfgiNcn4CRQ2jAjHDJkklNHoZCOX/3iCgGy+MXvf6PnLgRFK7IzRlRa6KMMdID9geDi7/iyJGdksw9UVXyxYQRjo+PL2v8Skv8srcuxhawK7OsKFPna1TwaPDqfPg/oboxh+qeTEIFQ3IjofPSJ4cKt09PP3mpb0vSb/qDi2yCSokCOeWHcOScxJoe01KmPN6Fgt6iDcq1IK9QnDlJSZYFUksZLsrRyn2YlLiqAPMW8xXC/Mpv1OMktRRC3ZGLOkQjyPR9Y8j4XQsg3cUQOam3xsKQcTYoQPlZ3zFCpNxHcAJO3iKlTNPyNJui7GBFzNKImX+JSRmmVPrxPKZNqoMP9y0ychjqbi3pttzgnt07fNL7eW2umdfYBjx2Ociz3tuDw/3zmj4Ra+3ylsL2VFMvIescQJoT7/csGGT3V5hnLCNjclX4wUVxPwCrFtRHNrpHH8yNlI+ANKD7ejFAIYDmg4tEHVado0niHa5k58uU/52Jl7oEswVI3KxQxLlucTI5+S7tVZ6Zo2JEOlFSFu8k7g00ec/hUdbrFa0bc6yt01WyhYzikOqzPw79/eRn1xq0S6r7i2gF6f8mFFDT34E9CyzIejC7ZtKGbmlsNUARj22KZSFHm6iyC2+C/QM="
   :ffd-offset "nVPLbhNRDPW588zMJJlpkrYk6WTyaNQUmCAUsUMwbFjCLwQoECkQqbQSqwgQfEU2rMumEl/QHR9C1iDRfbAnRHlU3WDJ9rWv7bF9z/TOo/OoF9+Ne9+Bcq0DWqECUR5w0W7iYAiDSCMjDwIpTUPcHbzpvzr6k2NPnbqIH/0g2nsKrnB7GYwsAg16iBmTMVc5aAqV3IMPRBGYnr8eDH9mxQyhcEO+bIjYEaEtIl3geDS6vDaObLkpy9kVsUeeqCDEZDKZ1bhLU+yKuyvKkmBHThkRejqvVsWz0dvp+H9KJRGXSr4uS/ljckJZ58wjm/L3z86+uXPbkqHfDUeXmRjVMvmS5QWwJU9qrfIqVSiHj4FEt6hB2S6kC34Mloq0goTU5hsuSWr1MXSKHZWHfo/3FUBdcI/CFAAS8c+dXO9OrrovNtzgR/ZsLcB8kyHZPo9iklK6brrpbkRYflHUFs+5aCNZ0atnnQxekKBrGlGT6uDkoUlaFuMUe+UUZA1GYP/0xeDXrQ1o7rD2mQ+5yMvB+5PT42ktzYhS6fCVwv4KRLeQsU8gUMOnvgmNrGGRt4YCDJ13zNOUxPwMbJvAF1Yp4p5ssJCCDbV4L5n3IV2l2MnIhNxqi3vJyk9ibfNJz0N5Gc8O0z5JmXwTO3fQ4TubuZL6q6lsbwy9SzfJkllKY6qv/8UpmHPrvjYdkkp+ExUx//kDCep4B7DWA/Pi99d9OjVSfKHVBoXM+xSJI0tN7LEJdxn7Fw=="
   :ik-fields "nVPLThsxFD1nJiHJjCczoYhHnpMAEQEaqiqLrkDDF/ALQQQUKTRRRNSuIv4jm34CEj/Bj7DqolXLnvoaqsxECgss+Vz7+Phe+/q6cxfehZ3253bnnizWWkSsfQB80mVzm3sDpgEbaZ8gLNtm+6h/3b3q/c1rpo4jtk8fgMoZtYePC+KiYRt6S3dy0f91OF/mF64G9OjApeXTUizlT26BkLpNRqPeH0+mVVrckhOlBTYE7P9KlxwPh09LdcjKSlHGrkAFSkyhytls9lzTR1yRecndFJMRsSOjnEDK5MEu83z49XH6blfRj7mrYAoVO/tg+O2NSzrviCjBctEoETGerZvu+Kq3PF9uLGQUxkM+n2BZyIeFfMklkY8CvIgL8E0emD6+lfVwkCX7Pxtcf62QDW0DKYfcvn74y/73m8n4sWbKJjToaIHF3YXC2tHW013cpHymlKscpZRXNXtgrWj+E1sas7qXDFs22FzwtIkDZPR4inryD5hs5JNcE/uwot/AGl++TkFELbXHTFLoCx8kuRQawpa50ySquu8iFMLDNit6Sneu/Qc="
   :ik-keys "nVPNbtNAEJ7PP3Fsr2O7QbTNr5u2UVMgRSgHTk3dMxK8QhChipSSKEoEp6hC7UNALjxCJV6AYw/wGD1xAAH3srMtih0pqOpKM+P99vP87WzrPDqPWs0nzdZnoFBtgBIrT+QDLuqb2OnDJNLJ9EEgTdfR3Osdd466v3MS2aA9NA8viMovID08WiAXFFqTv3Qmr3o/Hs6P8RQrATw45ELzoQkUc+0ToghyTYbD7i+PtxVoWOeMTFarrPR/TBcYDQZ/lvIoyycF/nZZlUmwCSuYzWZXVZlihvdFd42NxWSHv2xWhuqDXsLLwZvL6Z1dxZ/mroIpiUTu/cHb/xTp3CEiB7PjYSpislvjzuiou7xfbiJkHCVDXrVpWciLhX5xkZSLA7omh+SrPsDcP+HzqJ8Fet9ruH8zIavSBjwO9q68+Ne9d+PJ6LKqxiZS2pEEDduJyVmBnR2DBwHvOxnoZPUDOULIwzSQIXgU8vZUMi1oZ9LkOYf9Z1+/HdzMN+fO6/mCLMNIeeHWttc/fDz4MvfA9tZebJtMcNVbsihPCnfB8GEIVzhCCK+iSiYtI/HHaEidlVJUaEnp+sILW6MHZHFTwiltpB+xus5cGqvTLmnxT6J7uH77IZMaYgdWmugzHqQxg2qMlrBVB1WkbFPEgEebKMst3Dn3Lw=="
   :transform-fields "nVNLThtBEK03/s/0eGxIMP4PBiwMwUSRt9ierLPIFRzFIEuTjGVhJSuLaxhvcgSkXIKFuQMb1okIEsukqhOBxxIooqWu6n5V9V53q7p17p67reabZus7UKg2QAtjlcgBLNQ3seMjRhShmAMCGZEImgeDT73j/q80Ixt0gObbC6LyezDD/lJyQaM1LumNPw5+vHoIw0PJEqkVL8MQiunOKZELHie90XH/1pZ9BQbykhUTkxNzn2oCoyB4PI+SEinIWguVSYnLVjCbzX5X+YRx2RfNdXEJSTZllRIT1c8QKeFD8Pl6skjluf9H5V0sUTkTsu/5X2DVgaEW7jIeDvs3j17Geu5lvG+hEywq+sGXJxTVMxRFLOUNQ4oOZTvyCu1388uXSB7O55daJdPminiXK/ISV5389CzXnk7PtKQmTXe7TNrmikT3SvhcPwkcBb4f3NWw9q/HcuwzPHe5dY4GX0/Go+uqbjtXW5NDBraXGnOLvc1zTToxFXUQVZZSylZmRVeREefoazTYJnkWNVrStr7EtU57lOD1hDbCv0i/YDqM1WmXDO+ndMDfz5eVpIbaQSKc6AieCWNRqglawlYdVOG5Ta4ANm2izFtYD7l/AA=="
   :transform-keys "xVTNbtNAEJ7P+bMdJ04aaJp/N22jpkCKUK4kMWckeIUg0iqSi6MoET1FFYKHSHPhESrxEj2k78ClZxAgcYSZBbV2pCJUCWFpZrzfzM58uzu77TPnzGm3HrXaH4BirQkKfDkiG0iisYVdDzGiCMVsEEiLRNDaHx71Dwdf04xs0j5aT86JKs/BGR6sBBcVWucp/enL4af71264KCel1JqbYQildPeEyAF/k/74cPAtJeMqNBQkKiYqL+oq1ATGvn9zHOniKcq/KlQhS0y2isVi8aPGDOMyLpkbYhISbMqfISqqtiFSxgv/1eUsmMp1/i6Ve76Syp5R6ir/HeRsaFZgLdPRaPDlxsUkb7sY932IQbCi57/+Q0XrFhWlmOGOQhVtynZlFzpPlxd3oT9eLi9UlUyHZ8R7PKMgfqtbmJ/mO/P5qSqpkqZ7PU7a4RmJ3kfJ53g6cOB7nv+9jvXfPZZnm2HZ49Y5GB5PpuPLmmo7R2mTXRp2Ap23BkOfQDoJb/pxRCjhZTTGc4hFESeYlJXhW47Uob1jkxM2wlwWIRyFk7AW0s/YBoVWxv8KI8VMdltYCBthpdgxS2H735gZBsUgZ7PNm59iWZcLb0RtRK2kZVkpy6yqwyEtzt6HaLLWWUoKLSvdWHlLNugeJeT4sjPaDD9XqlXTYaxBe6S5n+Wq/XrlshLUtHaRCAfagmfCWJTqgpax3QBVWXbIESBFW6jwEMnr2J8="
   :event-fields "XVLLbtNQED3H19fOw05SimjJ023aqCngIpQ1afkC/gAF1UAkN67ShLKqqgq+Ihs+oRJ71nwIXReJii3MmEWbWpozd86cuZ479w4uo8toEL+IB9/IeqdP3PlWgSpZZq/LnZQWMLBCdFkyYSl1i9/PgcJQhB5PsvnkMD4d/YFxOEtOZm+Sj8nkxh1+BSw/JGl67ZNwjGG8Nz4avU9+V6rEBvYYv/oBtF5LFs9u/wIV13N2U0pG88Px9dN76TXxNbFdku/Gn2bz6VUnr4hyLGmv1uH2nd4fsFiYSc6QFyOPBn4aOMKv0rr0jhlILlTiM1kJyC/iagfHckZ6drgvhfsR4PI0m6Y/vZcyAFrHs0U5pHa0Jd2EYo/EXNklYKMyFFEkHXKaZTehRm06fKwDtgprCkYGKZm6rssKLQTqVtpcLBZ/O7Kfp3Gjua7OV3FJV0UFN78u0+TbbHJ1hnZ+fDieVD1nX7Ag1sjZZo69e5NcxxP4Op7wDBvLjyDvq7LM9bAL5+AX8JD/386KivrBDv1lYVX52jLnYlPZJrd6RFtsG5ESIbpsScjyrfYf"
   :constraints-order "nVPLTttAFL3HTkhijx8B2kCeJkBEoISqyiK7YL6gi/5AqqYoUtpEEVG7ihC/kU0/Aak/wY+wbtV2D/eOVWJbSithac7MnPs4d65nurfBbdDtvOl0vwPlRhsU+7aIPMBGax9HY2SJTMp6IJBhmuicjT4NLoe/XWb26Aydizui2ltwhtOUc1mzTQ4ZzD+Mfrxamc0eNn1yyCKbDA+GQsXtXxMF4G8+nQ5/ObKtw8CuVJQVKAmYfz1tYDaZ/FnrR3mxlGVtC9RIyVSsY7lcPjS4xA3ZV+wdmXLibMmqIJDRfTCreD/5fL94dqrw2yqVvyAVq308+fKPQ1rPUBSxQjhNKMa7dTWYXQ7X98uOSYZBXPKhT+sk71L9kkOSe35OkXORvKil2b62B+M8cBM28ZLThajqs21qk+M8NX+btvj6KcvjeMktUi+if6Dr9QV0/l3hlEDpqXhdnitgCZejmPK7SDl9+SylTOf/dV9E0XKzSzz7HNor9LxjvrIfR1+v5rP7hr7wgUaLXQwcpp7EAc8OD06E0M14yChb9JVT11FkbLD1NdqMeR4VzVY1tlK5duiEcrxe0F7y/epWuUmuRcdkhD+5u4iefVGc2uoIuaSjJ7yf5DLUFLaKgxaozuOQAiEc2keNt7BXvo8="
   :reference-scale "XVHLTgJBEKzaXVjkTTCCPJbltQHUxRjuhj/wFzA+QqKSoCSeiEf/gY+QxG/irAfv2j0edHeSru6uru70zEy2/tafhGfh5J2stYbEv1MGCmSGQZeDOyYAG4kCQVi2zXA8v5/dXj++5YVqY8xw+go0LygjTmLqmmE70jNbXc0/jmPlivii2Ijkzfz5abXctUyHbzAtJYv9WFNPfE7sQMyRNbOs589fpEeGcLlYfOU082jxUO+SUKgo2EhppaZxRqGJrLqSx81m892SeUnN6+WqOlfFaY32FBzzMnaDl4uH3Rqe2RBWUrpOORRMidUN2zAYxPau4giuxGu0o69ttspHuQAjWNNPYJ+/n1RS0TA7oBsVFpQvRjkHHWUb7AWEJ9aHr0QOXTYlZeZP+wM="})

(defn- advanced-fixture [name]
  (native/decode-graph (native/decode (.decode (Base64/getDecoder) ^String (get advanced-fixtures name)))))

;; Advanced model objects preserve every field and both reference tables exactly.
(deftest native-advanced-graph-roundtrip-test
  (doseq [[name packed] advanced-fixtures]
    (testing (str name)
      (let [original (native/decode (.decode (Base64/getDecoder) ^String packed))
            graph (advanced-fixture name)
            reopened (native/decode (native/encode-graph graph))]
        (is (Arrays/equals ^bytes (::native/payload original) ^bytes (::native/payload reopened)))
        (is (:complete graph))))))

;; Meshes, constraints and events survive both direct and actual editor save paths.
(deftest native-advanced-editor-roundtrip-test
  (doseq [name (into [] (remove #{:mesh-fields}) (coll/keys advanced-fixtures))]
    (testing (str name)
      (let [original (advanced-fixture name)
            source (native/to-spine-json original 0)
            updated (native/update-skeleton original 0 source)
            created (native/from-skeletons [{:name "independent" :source source}])
            document (data/import-spine-json (json/write-str source))
            exported (json/read-str (data/export-spine-json document))
            saved (native/update-skeleton original 0 exported)
            reopened (data/import-spine-json (json/write-str (native/to-spine-json saved 0)))]
        (is (= source (native/to-spine-json updated 0)))
        (is (= source (native/to-spine-json created 0)))
        (is (= (:bones document) (:bones reopened)))
        (is (= (:slots document) (:slots reopened)))
        (is (= (:ik document) (:ik reopened)))
        (is (= (:transform document) (:transform reopened)))))))

;; Vertex edits, normalized weights and per-influence deformation are all persisted.
(deftest native-weighted-mesh-edit-test
  (let [graph (advanced-fixture :ffd-weighted)
        source (native/to-spine-json graph 0)
        vertices ["skins" 0 "attachments" "slot" "image" "vertices"]
        deform ["animations" "a" "attachments" "default" "slot" "image" "deform"]
        edited (-> source
                   (assoc-in (conj vertices 2) -12.0)
                   (assoc-in (conj vertices 4) 0.5)
                   (assoc-in (conj vertices 8) 0.5)
                   (assoc-in (into deform [1 "vertices" 2]) 8.0))
        output (native/to-spine-json (native/update-skeleton graph 0 edited) 0)]
    (is (= (get-in edited vertices) (get-in output vertices)))
    (is (= (get-in edited deform) (get-in output deform)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (native/update-skeleton graph 0 (assoc-in edited (conj vertices 4) 0.25))))))

;; IK's one-channel Bezier form retains mix and supplies linear softness handles.
(deftest native-ik-one-channel-bezier-test
  (let [graph (advanced-fixture :ik-keys)
        source (native/to-spine-json graph 0)
        path ["animations" "a" "ik" "ik"]
        edited (assoc-in source (into path [0 "curve"]) [0.1 0.25 0.4 0.5])
        output (native/to-spine-json (native/update-skeleton graph 0 edited) 0)
        curve (get-in output (into path [0 "curve"]))]
    (is (= [0.1 0.25 0.4 0.5] (subvec curve 0 4)))
    (is (= 8 (count curve)))
    (is (< (Math/abs (- (/ 1.0 6.0) (nth curve 4))) 1.0e-6))
    (is (< (Math/abs (- (/ 10.0 3.0) (nth curve 5))) 1.0e-6))))

;; Event timelines are ordered across names rather than grouped into late batches.
(deftest native-interleaved-events-test
  (let [graph (advanced-fixture :event-fields)
        source (native/to-spine-json graph 0)
        edited (-> source
                   (assoc-in ["events" "second"] {"int" -9 "float" 3.5 "string" "other"})
                   (assoc-in ["animations" "a" "events"]
                             [{"time" 0.0 "name" "test_event" "int" 10}
                              {"time" 0.25 "name" "second" "int" -10}
                              {"time" 0.5 "name" "test_event" "int" 20}
                              {"time" 0.75 "name" "second" "int" -20}]))
        output (native/to-spine-json (native/update-skeleton graph 0 edited) 0)
        events (get-in output ["animations" "a" "events"])]
    (is (= [0.0 0.25 0.5 0.75] (mapv #(get % "time") events)))
    (is (= ["test_event" "second" "test_event" "second"] (mapv #(get % "name") events)))
    (is (= [10 -10 20 -20] (mapv #(get % "int") events)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"simultaneous event order"
                          (native/update-skeleton graph 0
                            (assoc-in edited ["animations" "a" "events" 2 "time"] 0.25))))))

;; Invalid advanced references fail instead of dropping or overwriting objects.
(deftest native-advanced-edit-boundaries-test
  (let [graph (advanced-fixture :constraints-order)
        source (native/to-spine-json graph 0)]
    (doseq [edited [(update source "ik" conj (get-in source ["ik" 0]))
                    (assoc-in source ["transform" 0 "name"] (get-in source ["ik" 0 "name"]))
                    (assoc-in source ["ik" 0 "target"] "missing")
                    (assoc-in source ["ik" 0 "skin"] true)
                    (assoc source "physics" [{"name" "physics"}])
                    (assoc source "path" [{"name" "path"}])]]
      (is (thrown? clojure.lang.ExceptionInfo (native/update-skeleton graph 0 edited))))))

;; The official editor discards triangles when a hull excludes an internal vertex.
(deftest native-invalid-mesh-hull-test
  (let [graph (advanced-fixture :mesh-fields)
        source (native/to-spine-json graph 0)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"internal vertex outside"
                          (native/update-skeleton graph 0 source)))
    (let [corrected (assoc-in source ["skins" 0 "attachments" "slot" "image" "hull"] 4)
          saved (native/update-skeleton graph 0 corrected)]
      (is (= 4 (get-in (native/to-spine-json saved 0) ["skins" 0 "attachments" "slot" "image" "hull"])))
      (is (= 6 (count (get-in (native/to-spine-json saved 0) ["skins" 0 "attachments" "slot" "image" "triangles"])))))))

(def ^:private physics-fixtures
  {:physics-default "nVLLTiJRED2nGwSbhvY5Ko+mQSTiA41haeIjce8v+LYTZjAqiW5wls434MJPMPEHXPohutbE2c9UXQ0IiRtvck/VPfd0VXXVrd0Fd0GtulKt3ZPpfIX4tEYBj0ywPM3ZOqOAjahHEJZts7oU/tw5OnhLCVPAEqubj0BumxJhsU+cNmxRPtlp7ocvC91rXrKYhc1Mau03EFDW3nFYf0rq0afFKa0jqjCh0FE65Gmj8fdLHeJ6k1Y/oZCDq2bYZ7vd/peXwgb0nHEm1cRU7Kg3qBAxf29nudv49dz6dqiN224or4XY1QMw1kliis1gXI2rvfNR2LpZ16bLT7ynjJtECo4SQwojeswjsnp9/SeHKZispoKkgmmP1ykWdlCPkyfHl2fh3luRPz5GMiF2SPacdPwwvDhvnj7nzZQCg45cWZzpm2NJbFK2Bol4tNyE6/hGD2tAuGVWdKSDcfEzhs8aLPfFmcQ8YuK3UOh9cKbTqV6ujDlYG6/SOr6/02EVVdxZxnqFnmlRLxdBUdksS2XClz2DQIkkppmTIxNd7X8="
   :physics-key-inertia "nVNLbhNBFKyaj2c8HnucBHDiz3jsJFYcwEHISwQYiR0SXMGET0YyOAqJRDYBIZDCGcyCI0TiAixZhFuQNUhhD+91kD+RsqGlrtevuqa7ul9P9yg5Srqdm53uF7JcbxNTbQGIyBxby1wb0AVsuBFBWLbNzkb6ov/86WlBmAY22Ln3Dag9oqxw/Zy4bNimfNLfe5L+vDaZ5j6bVdisFO68BRJK29xKBz/ymsa0uKQ+XIWSwlgZkDvD4e8LdfB1pqzjnEINoYa5mKPR6E9djGU0rwSLGjwVBzrKKjjm9HaVj4cvTw7+e6ne58lS0QG8N1+BS+NNjNkKLmsI9e5iNO5/uquXLoc429I3GykEShQV5jWtw7l1ePixhiWYXY2DvIK5nmhsFnYy8Mntrf1X6eZpk1f+laQksSh9XW78Wfp6d2/npG6qlBgMZMri6lSh5pn1d6kV4Lt+hja8QcESfoGuwwwYINL0PVks0fogwfi+/eD4+0OJ0900o+hti+L4AkU2C5fqdUXM5KWrdyeiFebCIDY2YWWEu8G2vqSsL+OK4asGW+ee4SKuwtOjSDEasy/dlLgwy7WwDqv3S2rGsx9kTkXtcI3erDAytZnlHDSVrXKlRcTSV5Eokccya5IyN9H+BQ=="
   :physics-key-reset "nVLdThNREP6+/elut9tuCyjQn+22QEMRizG9NIGaeO8rVFTYpFqCkMgNGiMJPEO94BFIfAEvfRC51gTvdeZgWkrCjZucb2a+MzszZ2a6F8lF0u087nS/kuV6m7jxzQIRmWNriasDuoANNyIIy7bZ2Ujf9HdeXRWEaWCDnaffgdpzSoSHt5zLhm3KL/3Dl+nP9ck1j9iswmalsPkRSCjf9m46+JFXM6bFRa3DVZhXGHsG5P5w+PtOP/h6U1Y9p1BDqKIUczQa/alLYRm1K8GCCk+dA9WyCo55vV3li+Hby+P/DtU7n4SKjuF9+AbMjZOYYiu4pyLU3sVoPPuypU2XR1yn9E0ihUCJosKMmnU4T05Pz2pYhMlqKsgrmPZE42JhJwOf3Ns9epduXzV5/99I5kUW5axJx1+n7w8O9y/rZkqJwUCuLK7cGNQMs/4BdQL81M/QhjcoWMLP0nWYAQNEan4mizKPExGlrV+S32i9PX11Fi4197IEz8vRWpyIVpgLg9ikhZUR7hHbuhlZX/SK4asGW7fWagEP4Glp0tzG9OaakRWmuRbWYPWkpjleL3xJndrhKr1px8j0eppz0FS2yuUWEctZQaJEHkusicncxPcv"
   :physics-global-inertia "nVPdThNBGD1nf7rb7bZbQAX6s90WaChqMaaXJlgTr/UVKopsUm2DkMANEKMJPkO98BFIfAEvfRC51gTv9fsG09Im3DjJd76ZM2dnzsw32zlPzpNO+2G785Us1VrEtbYARGSOzRWu9+kCNtyIICzbZnszfdN7/eqyIEwdm2w/+Q5Un1NWuD8jLhm2IZ/0Dl6mP+9NpnnERgU2y4WtUyChtO3dtP8jr8OYFpfVh6uwqDBWBuTeYPD7Rh18nSlpP6dQRahpLuZoNPpTE2MZHZeDJU2eigPtZRUcc3q7wheDtxfH/71U98tkqegY3sk34NZ4E2O2jNuaQr27GPWnnx/rpcshrrb0zUYKgRJFhXkd1uA8Ojv7VMUyzK7GQV7BXE80Ngs76fvkcPfoXbp92eCdfyVZlFyU2JAb30kP9w/2LmqmSonBQKYsrl0r1Dyz/j61Anzfy9CG1y9Ywi/QdZAZMpG5SIkPZDGh9VGSsbol8WwmTDOK7lAUpzcosnCpZlfFTV5CzTsRrTAXBrHxCSsj3AO29CllfemXDV8x2Jx5h0u4C0/PItWoTz91U+PCNNfEBqzuLykar/6QORW1wnV608LIFGeac9BQtsLVJhFLrCFRIo8VVmXI3ET7Fw=="
   :physics-global-reset "nVLLbtNQED3Hj9hxnDhNgbZ52E7aRk2BFKEskSBI7PmFUKC1FEjUh9RuCkIglW8ICz6hEj/Akg+ha5DKHmZuUdJUYoOle+bOmfHM3JnpnaVnaa97v9v7QlaTDnHlWwQissD2KjeGdAEbbkQQlm2zu5W9Guy8uCgJ08QWu4+/AY2nlAh3rzlXDduSXwaHz7Mfd2ZmHrNVh81a6eFbIKV827vZ8HtR1ZgWV7QOV2FJYeoZkHuj0a9/+sFXS1XvBYUGQhULMSeTye9ECsupXguWVXjqHOgtr+CY19t1Phu9Pj/571D9z7NQ0Qm8N1+BG9MkptgabqoItXcxmk8+PdKmyyMuU/omkUKgRFmhomoC58Hp6ccGVmCymgqKCqY90bRY2OnQJ8e7x/vZ9kWLt/6OZElkWc6mdPxldnRwuHeemCmlBgMxWVy/MqgK8/4BdQJ8N8jRhjcsWcIv0nWQGzMRW6TEe7KckB9EVPpj81qXmnJNYhblaAlORCsshEFsssHKCXePHV2IvC/3muHrBtvXtmkZt+FpRdLT5vzCmkmV5rk2NmH1f0rrebnnC+rUCTfozTtGpsXznIOWsnWutYlYzjpSJYpYZUNUFma+fwA="})

;; Physics records, including the verified zero-rotation NaN sentinel, preserve bytes.
(deftest native-physics-wire-roundtrip-test
  (doseq [[name packed] physics-fixtures]
    (testing (str name)
      (let [original (native/decode (.decode (Base64/getDecoder) ^String packed))
            graph (native/decode-graph original)
            output (native/decode (native/encode-graph graph))
            source (native/to-spine-json graph 0)
            rewritten (native/from-skeletons [{:name "physics" :source source}])]
        (is (Arrays/equals ^bytes (::native/payload original) ^bytes (::native/payload output)))
        (is (= source (native/to-spine-json rewritten 0)))))))

;; Combined rotation/shear influences and all setup flags retain their meaning.
(deftest native-physics-setup-edit-test
  (let [source {"bones" [{"name" "root"} {"name" "child" "parent" "root"}]
                "physics" [{"name" "spring" "bone" "child" "x" 0.25 "y" 0.5 "rotate" 0.3 "shearX" 0.7 "scaleX" 0.8
                            "limit" 1234.0 "fps" 120.0 "inertia" 0.7 "strength" 85.0 "damping" 0.8 "mass" 2.0
                            "wind" 3.0 "gravity" -4.0 "mix" 0.9 "inertiaGlobal" true "strengthGlobal" true
                            "dampingGlobal" true "massGlobal" true "windGlobal" true "gravityGlobal" true "mixGlobal" true}]}
        graph (native/from-skeletons [{:name "setup" :source source}])
        output (native/to-spine-json graph 0)
        actual (get-in output ["physics" 0])]
    (doseq [[field expected] (get-in source ["physics" 0])]
      (if (number? expected)
        (is (< (Math/abs (- expected (get actual field))) 1.0e-5) field)
        (is (= expected (get actual field)) field)))
    (let [document (data/import-spine-json (json/write-str output))
          updated (native/update-skeleton graph 0 (json/read-str (data/export-spine-json document)))]
      (is (= 1 (count (get (native/to-spine-json updated 0) "physics")))))))

;; Local/global scalar curves and reset keys use different native track categories.
(deftest native-physics-timeline-edit-test
  (let [properties ["inertia" "strength" "damping" "mass" "wind" "gravity" "mix"]
        tracks (into {"reset" [{"time" 0.25} {"time" 0.75}]}
                     (map (fn [property] [property [{"value" 0.5 "curve" [0.1 0.5 0.4 1.0]} {"time" 0.5 "value" 1.0}]])) properties)
        source {"bones" [{"name" "root"} {"name" "child" "parent" "root"}]
                "physics" [{"name" "spring" "bone" "child"}]
                "animations" {"a" {"physics" {"spring" tracks "" tracks}}}}
        graph (native/from-skeletons [{:name "keys" :source source}])
        animation (get-in (native/to-spine-json graph 0) ["animations" "a" "physics"])]
    (doseq [constraint ["spring" ""] property properties]
      (let [frames (get-in animation [constraint property])]
        (is (= [0.5 1.0] (mapv #(get % "value") frames)))
        (is (= [0.1 0.5 0.4 1.0] (get-in frames [0 "curve"])))))
    (is (= (get tracks "reset") (get-in animation ["spring" "reset"])))
    (is (= (get tracks "reset") (get-in animation ["" "reset"])))))

;; Invalid physics targets, references and unsupported step ranges fail before writing.
(deftest native-physics-boundaries-test
  (let [source {"bones" [{"name" "root"}] "physics" [{"name" "spring" "bone" "root"}]}]
    (doseq [modified [(assoc-in source ["physics" 0 "bone"] "missing")
                      (assoc-in source ["physics" 0 "rotate"] -0.1)
                      (assoc-in source ["physics" 0 "mass"] 0.0)
                      (assoc-in source ["physics" 0 "fps"] 60.5)
                      (assoc-in source ["physics" 0 "fps"] 1001)
                      (assoc-in source ["animations" "a" "physics" "spring" "unknown"] [{"time" 0}])
                      (assoc-in source ["animations" "a" "physics" "missing" "mix"] [{"value" 1}])]]
      (is (thrown? clojure.lang.ExceptionInfo (native/from-skeletons [{:name "invalid" :source modified}]))))))

(def ^:private path-fixtures
  {:path-default "nVRPbxJREJ/f7sIu7C6whVLkz7JAS0pVagx6M6Y0JD36FahaQ4JiSDGe0Pg1uHiulyb6Bbz1i5B400QTjzjz+Net7aUv2Zk382bnN//ea50H50Gr+bDZ+grkyw3QpZUmSgI26jXs9hEh0imSBIE0XUdzv/e6++rl7wRrKrSPZvuCqPQM7OH+2hguPB2GjxmvyJwloGsoJJ5+JArAazgY/HFF8qHhjgBHhGwJ0cmSk7zsbSElcoR5PiaTyazM4FGRC3ZOmCnGcdnFhBgqDb2I48Gb6ZjivqQ3c8ii5JOzsy/2XDYliLfd0x+xDrIOp6wfHs0L4CItrMxxf6DZpjgvPoJBnZiWRPQxh+tB+x4syuUBYrFQr6oYVrfZuv3tf/XFtepD51onh9mwGlxox9I9qKx9slLQNMtWxRBi5jLCNjw4q2gXaxnPMoAl4hLCoAhX5/lo+G4aUI0qIDPVj5LuYqwGIa86XuVx6I5e9H7eu9T6I2QTQFSQVSNM1VUR7XmguqnAONjcquv8n+qfarsrOy615iznxQY+dW8cFuu2w3LweT0syTGF4I5vhMvcAk6QYge/QnCkB/0N4GTQ7w/+VpFd3LEtqWE6xdIet+Ck9/50NJyWVb0DReN8pGHnytXcZu7KzTPFkcHj7FhOxtn01T+kRfmsE3uABp9a/BWUvqho/YqvHN0lk/djqoSfBpV5Iqyr0x5pkloG8xfFE6OGswszbJgUfSqsM6iqrhe26yCfvx0K5iNQQ4lF2Gvbfw=="
   :path-weighted-two "nVRBbxJBFH7f7sIu7C7LWkoptMtCW1KqUmOqN23ahqRH/wJVa0hQTNMaT2j8F8rFc7000T/gjUP9GSTeNNHEI743C1JoSUwnmffmzXwz89733szWWXgWbtXu1rY+A/liFXShzRF5gI3KCtZbiBHpFPNAIE3XUdtsPm88e/orxTMl2kRtt0e0/Ah8wu0xGC58HUaAAbdYpFLQNRRS22+JQnA7ard/u2IF0LAoF8dELIjQyZKVvIxtEcvkiPIDdLvdQZEvj4tdsHOiTAEnZZQQYagw9CUctF/0O5QMJLyBQxZ5D05PP9mRbYoTLxvH3xN1ZB0OWd/bjwhwMSeqyH6/ocG8HL50DwbVE5qH+H1214fxNYzAI00+tO3F9x8enp9/G0Joul2G7PLu3S/ch/oqSG8I6c2E7DnR+SN9FSQ7hGQvQ+BBcyxnlBwbeNeYmRnrupnZ+TjOjNch3YdKVgArDU2zbIUUYeYyom74GIXz382gGCf18cnRq35IK1QCmelWnHQXHVW/eVWoZa7ixsmT5o9bFyp2H9kUEJeblZemCllMO3JUN5U77GzuHyW8TwWnOHFlpAmX/gSXBzO59K/BpdCY2Pk5wSXpYWsOOGy3Wu0/ZWSHr3VBwsqk2dpgVg6br49PjvpFRUGoZJKXNKxNPfJV1q68YVMOMvhhcHH4znyg9pAW57V64g6qvGpxL6j5JSUrU2fl6CaZPO5QafKTUZGnJucqtEGahJZB9Df5Aqo66zAngZ7MpyfnDCqrh4rVCijgvkZhlJUVLLMJe4z9Cw=="
   :path-weighted-deform "nVRNTxNhEJ5nd7u7ZbcfS6FsKbTLVwOohRg0XqRdCAkHE/wJFhVDUi0hYDxVY/TuVbl4xguJ/gC9cYDEn+CFxJsmmnisM++2QvlIDG8yMzvvPPvuzDOz79xesBfMla+X5z4Cg8Up0ImVIUoBDkpjmKwjRqRTLAUCabqO8sz649qjh7+SvDNCMygv7BMN3wWfcO0YjAQ8HUYBLV6xyCSha8gnKy+IAvDabDR+J8QrQENOPhwTNSBKJ1sig/LsiBomV4xXwM7OTqvIHzfFzzu+GEvAPfIUF2WoMvQhrDaeHDWppyDltVyyKXV7d/eDE/mWJLFR2/oeX0LW5ZL1xeWIgAQyYoqc93Nq9cvhQzdg0FJcS8G8yel6ML4EEbhjyYNWyb19N39wcNiG0Ol1FrLAby98Ymnb8yD7bcj+hZBFNzq/Y8+DZNuQ7FkIUtBc2+00xwFe1i7sjH3ZzoTvjzuTapLuQTWrADsNTbMdhRRl+X1iej10yvnvZVCMm3p/e/PpUUBjNAKy0nWT9ASaan4H1aCO8hTXth+s/7h6YmKXkU0CpnxZZWmpksV1okR1S6XDyfr/KOH3VHGKk4Q8acKl18Xl6oVcepfgUmiMhz+7uCQ9qGeAtUa93vgzimz7bx2QsvrS7E0zK2vrz7a2N4+KioJA6R4OaZg4wUIv4vYWR3SZARM6WXWfS0IGMQMmwaacuK/41jChvWaTlyxW2lKtEs3fOTiUPcAnBQg3iEIOVDjQASqq4EPxUBHh2iqf+QQ5JYhOqt5j4f3qGxYuu7rHIpivLN9YmIWQ8aHNkmZhmkJ+N5xkmWW5RfzPxmUmmI1xLjYhV5Ml/Bj8v/PMe25/QVFBmsmxpfgspjhqs+TV/pDSpVP3oE9XyBK6ck0a6b49VUuT3XslmiZNetaH6NL1BDTlTsLqBqZkP929Z9CouoEwXgIVWCYoiMZtDMPswjnG/gU="
   :path-multiple-curves "nVTNbhJRFD7fzMAMzAzD9AcQ6DD8lJSq1Bh0p6YQki59BarWkKAYUoxxgcY3cM3Gdd000RfQFS9C4k4TTVziPRcGOpVuepM5555zz5zf797GuX/uN+p3640vQLZQA11Ym0QOYKJaxl4PESKVIg4IpKgq6gfdF53nz34nhKZIB6g3J0Q7jyE83F4Zw4arQvMwEysyZwmoCnKJR++JfIg16Pf/2Cx5UHCDA0eYpJmoZPBJlvcmkx2ymLkexuPxrCCCR1nOmRlmOhvHeRdjosky1DyO+y+nI4p7XN7MIoOcB2dnn825rHMSrzqnP2JtpCxRstY6ImpN5k2wscmsIHJ/R7NtDpC/B43aMcVB7L5I2YXyzV+0zAXYYqFedjKsbgrr5tf/1ZO16pa11kkrtV79kOj7Gidv16s/hp1ATMwyVBeyfR4ZSWiKYSRM2VcmemWL2YaLjWXRixWUFdQRJB5kGqQW5BIE1ygiBvBkOHg99alMRZCe7EVJtTGSWMtKUJUE4jrDp92fty6g6wipBBDljOSsdQkcFs15Caouk0hCySyBJf6TEJHIsnknJqlYASRN4EPnSjwa18Xj4acVHp0RhcIdXxkufY1wHCl2+CsUjlS/tw2c9Hu9/t8SUotrnOYeppJC2hcjOOm+OR0OpgXZb1/SuDhSsHvp9lcEt/ly6+xIc6BahpW2Mp78h5SoOGvH7qAmTg3x5aQ+L2n1kq8M3SRd7EdUDL8+svJEWFelfVK4tC3MHy2XjWrWHvSwocP6ZFinUUneXlSqIE98u+TPIVDGjhBhrmz/AQ=="
   :path-closed-multiple "nVRLbxJRFD7fzMAMzDDD9AHIYxgeJaUqNQbdqSmEpEv/Aq3WkKAYUoxxgcZ/4JqN67ppon9AV/0jJO400cTEDd5zYUqn0k1vMufe890z53W/e5un/qnfbNxtND8D2WIddGGsEzmAiVoF231EiFSKOCCQoqpo7Paed589/WULpES7aLTOiAqPITzcXhojAVeF5mEmRmQ+2VAV5OxH74h8iDEcDH4nWPOg4AYHjrBIs1DJ4J0sr00WBbJ4cj1MJpNZUQSPsp4zMzzpbBznVYyFJstQ8zgYvJiOKe5xeTOLDHIenJx8Mue6zkm87B5/j3WQsuBAa+8TtUU57b9ECayzk6LI/S3NNjlA/h406sQUB7H7ImUXyld/0TIXYIsFfN7JMNwS1q0v/8NnK+G2tdJJO7Uafkj0bYWTN6vhD2EnonhYhupCts8jI4moYhi2rZiysyz06gZPay7WzstejKCwoJIg9SDXILkgmyC8RhFxBIej4aupTxUqgfRkP0pqAmPJtqykVVlwrjt60vtx6wK/9pGygShnJE9bl9Rh1ZwXoeoyiSSUzDm1xH+SJJJbCV6Js1SsgJQm8L57JSON6zJy7+OSkc6YQuEOrgyXvkY4jhTb+xkKR6rf3wSOBv3+4E8ZqcVFTnMPU0mh7YgjOOq9Ph4Np0XZb1/KuNhSsHXp/lfFnODrrbMjzYFqGVbaynjyH1KiYq8Tu4O62DXEl5N4XsraJV8Zukm6WI+pFH5/ZOV2GKvRDilc2gbmz5bLRnVrG3rY0GE8GcY0Ksv7i2oN5Ilvi/w5BSooCBXm0vYf"
   :path-multiple-skeleton "tVZPTxNREJ95u+1uu9t/UEop3XbbQgMUijHoTQk0JMTExMRPUPwXkmoNAb1V47dQLp48wIVEvwA3DvgxSLxhoonHOvO2u+32DyUSX7Lz3pudfTNvZn6/7NqJfWKvVW9X174KzOQXEXrGJEAMhYHlEi40MACgQCCGgCAUBauruy/rL579ipKmAKtY3TwDsB4hnbDSNcYIJhRUc9imEXCmKCoCZ6Pr7wFspLHXbP6O8C6HAmfYcYDFNAsFdH6T4bXBwgKTp0QODw8P23lyHuT9rJHmSWPjMK9CLFR5DSWLO81XFy0I5/h6bRN0iN07Ojo2nL3GQbyu7/8IbWHKpCsrtQeZL7XjyOMITvIBeYr7HbSn+PDsHVRhKyRiGLxL4SZQnNqddCUQ2aKj9rLoV2+S9ea3QfXZUHXNHHpILeVXIyXa1JUEylvnQI+jELohk8FCSyd5mkig6UXbGW48bgCuR9eFCgHKzpODvTcXNpSggKDFG0FQItiSjZCRFS9SO9QPnu5eLveUfhtTUcQge5aF0GRVeWs4gSqadEbBpr2q03eyfrLsEV5RqoXp9ouB+KE+sln0f22Wjc/dZom1wOduZ6S75D+4Y0+hjZ8+d6DYjQnE581Go/mniKkOxqY5h5Nx2i1RCaiD8zLXtpRhUguc74PlHM0RRp3Gh6jUyqZuJs2pnPwGRJDebYVu4SK91emZlfqslOW+s9JQAY3WLRjGANaNGCAsejL8v/FvjcP/RD/8t53eHwn/Uhf+6qntGLszYVKsz3z8dP/8/HvHBPrHoIkHQ9vjgQGTMz9Sh5h44DVHOnJx3UMhngky0orXxFrx5lizsg9b4LEWXsFaAxkcMwZZqxBv2GB1WMsaw1pLV5FWwSGtSg9nLQ+jrMo1WaRycxaRiQTLbqwMsIglWaTqssj+28uxLGJ5LFLwWKRI11n1sUjpWixidVmk4P+5kLeO+nVlWALB10qi80+SYKNFcwE1v2GM9XG/ToWiRCjOlRFy9MyD7VSkhBZt0eja/gU="
   :path-key-mix "nVRPTxNBFH+/3W13l93+WVpKS2G7/GsAtRhBb1IKIeFgol+hqDUk1RICxlM1Rj+EcvGMFxL9At44lI9B4k0TTTzW92bb0hW4OMm8mfebN/Pe/N6bWT0JToLVyp3K6hdgorQIGmoZohTgoDyLhSZiRDrFUiCQpuuoLO8+rz97+ivJyDQto7JxSjT1CHzCrQtjJODpMHx0ucXCIQldQzFZfUMUgNt+q/U7IZoPDQVxHBMxLkInS1YmZO6ImCJXBs/H0dFRt8TO46IXnbwMphiPyMwWYahr6JPYab04b9OIL9frumRR6v7x8Wcn1E0JYq9+8N3eQs7lK+ub2yEBCWRkKHHcr6k7JodP3oVBW7aWQvweh+tB+xb06PIAsejBAxaj8AZbb3y9DJ9eCW+6Vx6ymYvCYKJdS/egbu2TlYamWY4iQ4SZz8ow6sEdRNtr/Xj6AfQ99l0YFGN2Hh/uvzwPaJamQWa6GSc9gbYqhAmV8Rkuh/rhk90fN4dSv41cEoiLZ5UIU2VVVCcMVDeVMw42P8g671P5U2lPyIyp1tx+vTjA2/q1xWL9b7HUPl0US6pNEXc717rL/oc78WTXfkbckR40R4FGq9ls/ZlBrvfGxoXDTJq1JU5BY/fVweH+eUnxHSg5wksa5ocoH4VtHfCKLjTFoZPZzDN/yCBmIE6wqCDqO37rDrT3PBQlirUHnbO1TuesWvjw8SHr0tfXQ5x6+gDrXMZkn2BQJ9b2GOGd1ZWVlWqj0aith3p/hzSF8foljO2HMcrYUoDMxhxfNiEfiin8GPxKXcvNumO+ooK0OK9t2bexyKsW96LCJ5Us//N75ekGmUJXoU3T0T9PpTQZxcq0RJrkLIvwq/TEaNFdgBk1TAmejmIGzah/A3NlkM99noKwtmcxxSqcC9u/"})

;; Paths preserve shared option references across weighted geometry and multiple skeletons.
(deftest native-path-graph-and-editor-roundtrip-test
  (doseq [[name packed] path-fixtures]
    (testing (str name)
      (let [original (native/decode (.decode (Base64/getDecoder) ^String packed))
            graph (native/decode-graph original)
            output (native/decode (native/encode-graph graph))]
        (is (Arrays/equals ^bytes (::native/payload original) ^bytes (::native/payload output)))
        (doseq [index (range (:skeleton-count graph))]
          (let [source (native/to-spine-json graph index)
                created (native/from-skeletons [{:name "path" :source source}])
                cyclic (or (contains? #{:path-weighted-two :path-weighted-deform} name)
                           (and (= :path-multiple-skeleton name) (= 1 index)))
                editable (if cyclic (assoc source "path" []) source)]
            (is (= source (native/to-spine-json created 0)))
            (when cyclic
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot depend on a constrained bone"
                                   (data/import-spine-json (json/write-str source)))))
            (let [document (data/import-spine-json (json/write-str editable))
                  saved (native/update-skeleton graph index (json/read-str (data/export-spine-json document)))
                  reopened (data/import-spine-json (json/write-str (native/to-spine-json saved index)))]
              (is (= (:path document) (:path reopened)))
              (is (= (:slots document) (:slots reopened))))))))))

;; An open path's trailing native length entry is padded without changing its curve.
(deftest native-path-short-lengths-and-closed-edit-test
  (let [graph (native/decode-graph (native/decode (.decode (Base64/getDecoder) ^String (:path-default path-fixtures))))
        source (native/to-spine-json graph 0)
        path ["skins" 0 "attachments" "path" "curve"]
        shortened (assoc-in source (conj path "lengths") [200.0])
        output (native/to-spine-json (native/update-skeleton graph 0 shortened) 0)
        closed (-> output (assoc-in (conj path "closed") true) (assoc-in (conj path "lengths") [200.0 400.0]))
        final (native/to-spine-json (native/update-skeleton graph 0 closed) 0)]
    (is (= [200.0 0.0] (get-in output (conj path "lengths"))))
    (is (true? (get-in final (conj path "closed"))))
    (is (= [200.0 400.0] (get-in final (conj path "lengths"))))))

;; Path constraint edits and all three key channels are persisted independently.
(deftest native-path-constraint-key-edit-test
  (let [source (native/to-spine-json
                 (native/decode-graph (native/decode (.decode (Base64/getDecoder) ^String (:path-default path-fixtures)))) 0)
        frames [{"value" 0.0 "curve" [0.1 0.0 0.4 1.0]} {"time" 0.5 "value" 1.0}]
        mix [{"mixRotate" 0.0 "mixX" 0.25 "mixY" 0.5 "curve" "stepped"}
             {"time" 0.5 "mixRotate" 1.0 "mixX" 0.5 "mixY" 0.75}]
        source (-> source
                   (assoc-in ["path" 0 "positionMode"] "fixed")
                   (assoc-in ["path" 0 "spacingMode"] "proportional")
                   (assoc-in ["path" 0 "rotateMode"] "chainScale")
                   (assoc "animations" {"a" {"path" {"follow" {"position" frames "spacing" frames "mix" mix}}}}))
        output (native/to-spine-json (native/from-skeletons [{:name "keys" :source source}]) 0)]
    (is (= "fixed" (get-in output ["path" 0 "positionMode"])))
    (is (= "proportional" (get-in output ["path" 0 "spacingMode"])))
    (is (= "chainScale" (get-in output ["path" 0 "rotateMode"])))
    (doseq [property ["position" "spacing"]]
      (is (= [0.0 1.0] (mapv #(get % "value") (get-in output ["animations" "a" "path" "follow" property]))))
      (is (= [0.1 0.0 0.4 1.0] (get-in output ["animations" "a" "path" "follow" property 0 "curve"]))))
    (is (= "stepped" (get-in output ["animations" "a" "path" "follow" "mix" 0 "curve"])))
    (is (= [0.5 0.75] (mapv #(get % "mixY") (get-in output ["animations" "a" "path" "follow" "mix"]))))))

;; Invalid path references, modes and missing curve lengths fail before persistence.
(deftest native-path-boundaries-test
  (let [source (native/to-spine-json
                 (native/decode-graph (native/decode (.decode (Base64/getDecoder) ^String (:path-default path-fixtures)))) 0)]
    (doseq [modified [(assoc-in source ["path" 0 "target"] "missing")
                      (assoc-in source ["path" 0 "spacingMode"] "unknown")
                      (assoc-in source ["skins" 0 "attachments" "path" "curve" "lengths"] [])
                      (assoc-in source ["skins" 0 "attachments" "path" "curve" "vertexCount"] 5)
                      (assoc-in source ["animations" "a" "path" "follow" "unknown"] [{"value" 1}])]]
      (is (thrown? clojure.lang.ExceptionInfo (native/from-skeletons [{:name "invalid" :source modified}]))))))


;; Native skin memberships must fail explicitly instead of changing constraint activation.
(deftest native-constraint-skin-dependency-read-test
  (doseq [packed [
    "nVTNbtNAEJ7P68RO7fy4TZOQpI6TtFFbIEUocEOorSr1Bq+QFooiBYKiFnEKiNeIhDiXSyV4AW59kQhuIIHEjTCzzk8N7aUreWZ3dna/2W9m3DoLzoJW826z9QkoVjZAF8YSURpw0KhjvYsYkaJYGgQylEJzq/O8/ezpzxRbqrSF5s450cpj8A23585IwlMwfYx5xEKVgjJQSj18SxSAR7/X+5WUlQ8DNwQ4JiIvQpEtO0WZOyJWyBXl+RgOh+MKg8dlXXIKoixxXpBZQoSpn6HKOOi9GA1owZfnjV2yKf3g9PSjE64tCeJl+/hbYg85l5+sdvdDApJYElXhuN/QeFkuL9+DSXsJI434fQ7Xg/ElmNDlAeIxMc9YjJp32Hvn8//m80vNu+6ll+zmomYw0a6tPOhX+2RnYBi2o8kQYRWyohY9uLNoJ2MazzSAKeIUwqQYs3N40n81CqhOVZCV6cZJJTHQhVDUGa9xObRPnnS+37qQ+n3kUkBckHUiLJ1VWTphoMrSYBxsYZZ1Pqfzp9OelBlTbbjTenGAd+0ri8W+brFsf5gXS3pAEbiDK+Gy14ATpMT2jwgcqaC7CBz1ut3e7xpyzEENCUsoXOL26Hy1ffx5/2hsIs53kmJGKHaB6FpOMhHmI69PZdhjk/N21Hl9fNIfVXSSAi0XeMvA2j/9vMo6Ke1qMTqZ3AOu7WbdZV+fIYOBsZe4gw3etTVkSe+UtWx08wW6SRwxDaga/YlojlJRW4M2yRASsgj/PZ44bbjrsKKOabFnojaTaroRsdoA+fytURAWSx0rvIQz9/0L"
    "nVPLbtNAFL3Hjyaxx48AoiUPx3XbqGkhRSgLVlTmB+AXggiVpUCiqFG7ivofkap+QiV+oj9SsWBBBawJ906FYkcKi440d2bOPTNnfH2mdx1fx73uq27vK1BrdUC59pgoAFy0d7A/hE1kkh2AQIZponuUfe6fDH75jGzTEbpvb4ia78EnvFgh1zSa8Jb+9GP24/kyjdd4FMKDQy6MAIZC3T++IIrBbToeD356soxg4JncyJawKcH8x3SByWj0ey2PypKpydyV0CQlQzXCfD5ftPiKG7Kuu1sylITsyKwiwdJ1MBv4MPpyO3vwUenV8qhwRip39+Ho7D8f6TxAUcQq6bigmK/WaX9yMlhfLzcnmcZ5ycUxrZO8WamXfCT5aUj35CoFug6w31xIPh6Wgex7gqdsgQSVkjihwj8y+1aO8Ofy3cLCBiuRyaYgO+eXxBdDzbStNjkX6p0H7JZP2fnpdHLb0l6LdXSYYGBvxY27PHrcWZusAJZylaOU8iK9hwwRfokOx7IWrGu8oWN7GGzRIfF9aUbbxdei6+YXsTYdkJHeET3B/SOrCqmj9lEqEgPBwyJmUSJoA7ttUMR9j2IBPNpBk5dwl9y/"
    "nVJdTxNRED1nd0u3290u+AX0Y7tdoKGoxZg+migmPutfQPxgk0INSiIv1Uf8DTWEn0DiH/DRHyLxURN8FmeGpKUkvrjJPXNn5uyduWdu7yQ9SXvd+93eF7La7BCXvutATJbZXuJqnwXARSEmCMd12V3PdzZfvzyrSKSFdXYffwMazygn3L1Crlo0k18291/kP+9M0jxgVofLWuXhRyClfFvbef97pG5Ch4vaR0FhXmHMDMi9weD3P3nwNVPVfVmhgVDNXMLRaHTelMZm1K8FC2qKSg50V1Lw7PZunc8Hu6fD/z5q43hyVDxE8cNX4Ma4iDVbw001oWqXoPXk8yMVXS5xUdK3QgqBBmYVrqnbhPfg8PBTA4uwqtZBpGDyxONm4aZ9n3yzffA23zrLeEs0z1gqqvQl0TD/4Sf8c/T03OOM3ASuI1i4NKAs1AkObY7zkpuVtSZjepW/f7e/d9q00aaGgaQcrlwZ/rLYSJZUhhfTCcthkBgfjpa8x44141uxmmXqhu1+tIDbkF4xRGv6adpMKtOxNtbgbPwSkXnxoueU1AlXWZwmxibmdMxDptE6l9tEImsFqQYiLLEhLssT7l8="]]
    (let [graph (native/decode-graph (native/decode (.decode (Base64/getDecoder) ^String packed)))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"constraint skin dependencies"
                           (native/to-spine-json graph 0))))))

;; Unverified constraint activation flags cannot silently become always-active constraints.
(deftest native-constraint-unverified-activation-read-test
  (let [graph (native/from-skeletons [{:name "flag" :source (native/to-spine-json
                  (native/decode-graph (native/decode (.decode (Base64/getDecoder) ^String (:path-default path-fixtures)))) 0)}])
        index (first (into [] (keep-indexed (fn [index node] (when (= :path-constraint (:kind node)) index))) (:models graph)))
        altered (assoc-in graph [:models index :data 34] false)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"activation state"
                         (native/to-spine-json altered 0)))))
