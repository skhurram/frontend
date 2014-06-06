(ns frontend.components.build-steps
  (:require [cljs.core.async :as async :refer [>! <! alts! chan sliding-buffer put! close!]]
            [frontend.datetime :as datetime]
            [frontend.models.action :as action-model]
            [frontend.models.container :as container-model]
            [frontend.models.build :as build-model]
            [frontend.components.common :as common]
            [frontend.utils :as utils :include-macros true]
            [om.core :as om :include-macros true]
            [sablono.core :as html :refer-macros [html]]
            [goog.string :as gstring]
            goog.string.format
            goog.fx.dom.Scroll
            goog.fx.easing))

(defn source-type [source]
  (condp = source
    "db" "UI"
    "template" "standard"
    source))

(defn source-title [source]
  (condp = source
    "template" "Circle generated this command automatically"
    "cache" "Circle caches some subdirectories to significantly speed up your tests"
    "config" "You specified this command in your circle.yml file"
    "inference" "Circle inferred this command from your source code and directory layout"
    "db" "You specified this command on the project settings page"
    "Unknown source"))

(defn output [out owner opts]
  (reify
    om/IRender
    (render [_]
      (let [message-html (:converted-message out)]
        (html
         [:span.pre {:dangerouslySetInnerHTML
                     #js {"__html" message-html}}])))))

(defn trailing-output [converters-state owner opts]
  (reify
    om/IRender
    (render [_]
      (let [trailing-out (action-model/trailing-output converters-state)]
        (html
         [:span {:dangerouslySetInnerHTML
                 #js {"__html" trailing-out}}])))))

(defn action [action owner opts]
  (reify
    om/IRender
    (render [_]
      (let [controls-ch (get-in opts [:comms :controls])
            visible? (get action :show-output (or (not= "success" (:status action))
                                                  (seq (:messages action))))
            header-classes  (concat [(:status action)]
                                    (when-not visible?
                                      ["minimize"])
                                    (when (action-model/has-content? action)
                                      ["contents"])
                                    (when (action-model/failed? action)
                                      ["failed"]))]
        (html
         [:div {:class (str "type-" (:type action))}
          [:div.type-divider
           [:span (:type action)]]
          [:div.build-output
           [:div.action_header {:class header-classes}
            [:div.ah_wrapper
             [:div.header {:class (when (action-model/has-content? action)
                                    header-classes)
                           ;; TODO: figure out what to put here
                           :on-click #(put! controls-ch [:action-log-output-toggled
                                                         {:index (:index @action)
                                                          :step (:step @action)}])}
              [:div.button {:class (when (action-model/has-content? action)
                                     header-classes)}
               (when (action-model/has-content? action)
                 [:i.fa.fa-chevron-down])]
              [:div.command {:class header-classes}
               [:span.command-text {:title (:bash_command action)}
                (str (when (= (:bash_command action)
                              (:name action))
                       "$ ")
                     (:name action)
                     (when (:parallel action)
                       (gstring/format " (%s)" (:index action))))]
               [:span.time {:title (str (:start_time action) " to "
                                        (:end_time action))}
                (str (action-model/duration action)
                     (when (:timedout action) " (timed out)"))]
               [:span.action-source
                [:span.action-source-inner {:title (source-title (:source action))}
                 (source-type (:source action))]]]]
             [:div.detail-wrapper
              (when (and visible? (action-model/has-content? action))
                [:div.detail {:class header-classes}
                 ;; XXX: better way to indicate loading
                 (if (and (:has_output action)
                          (nil? (:output action)))
                   [:div.loading-spinner common/spinner]

                   [:div#action-log-messages
                    ;; XXX click-to-scroll
                    [:i.click-to-scroll.fa.fa-arrow-circle-o-down.pull-right]

                    (common/messages (:messages action))
                    (when (:bash_command action)
                      [:span
                       (when (:exit_code action)
                         [:span.exit-code.pull-right
                          (str "Exit code: " (:exit_code action))])
                       [:pre.bash-command
                        {:title "The full bash comand used to run this setup"}
                        (:bash_command action)]])
                    [:pre.output.solarized {:style {:white-space "normal"}}
                     (when (:truncated action)
                       [:span.truncated "(this output has been truncated)"])
                     (om/build-all output (:output action) {:opts opts
                                                            :key :react-key})

                     (om/build trailing-output (:converters-state action) {:opts opts})

                     (when (:truncated action)
                       [:span.truncated "(this output has been truncated)"])]])])]]]]])))))

(defn container-view [{:keys [container non-parallel-actions]} owner opts]
  (reify
    om/IRender
    (render [_]
      (let [container-id (container-model/id container)
            controls-ch (get-in opts [:comms :controls])
            actions (remove :filler-action
                            (map (fn [action]
                                   (get non-parallel-actions (:step action) action))
                                 (:actions container)))]
        (html
         [:div.container-view {:style {:left (str (* 100 (:index container)) "%")}
                               :id (str "container_" (:index container))}
          (om/build-all action actions {:opts opts :key :step})])))))

(defn container-build-steps [{:keys [containers current-container-id]} owner opts]
  (reify
    om/IRender
    (render [_]
      (let [non-parallel-actions (->> containers
                                      first
                                      :actions
                                      (remove :parallel)
                                      (map (fn [action]
                                             [(:step action) action]))
                                      (into {}))
            controls-ch (get-in opts [:comms :controls])]
        (html
         [:div#container_scroll_parent ;; hides horizontal scrollbar
          [:div#container_parent {:on-wheel (fn [e]
                                              (when (not= 0 (aget e "deltaX"))
                                                (.preventDefault e)
                                                (aset js/document.body "scrollTop" (+ (aget js/document.body "scrollTop") (aget e "deltaY")))))
                                  :on-scroll (fn [e]
                                               ;; prevent handling scrolling if we're animating the
                                               ;; transition to a new selected container
                                               (let [scroller (.. e -target -scroll_handler)]
                                                 (when (or (not scroller) (.isStopped scroller))
                                                   (put! controls-ch [:container-parent-scroll]))))
                                  :scroll "handle_browser_scroll"
                                  :window-resize "realign_container_viewport"
                                  :resize-sensor "height_changed"
                                  :class (str "selected_" current-container-id)}
           ;; XXX handle scrolling and resize sensor
           ;; probably have to replace resize sensor with something else
           (for [container containers]
             (om/build container-view
                       {:container container
                        :non-parallel-actions non-parallel-actions}
                       {:opts opts}))]])))))
