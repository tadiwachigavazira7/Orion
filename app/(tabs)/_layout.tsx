// app/(tabs)/_layout.tsx
import { Tabs } from 'expo-router';

export default function TabsLayout() {
  return (
    <Tabs screenOptions={{ headerShown: false }}>
      {/* 1. Main Landing / Home Dashboard */}
      <Tabs.Screen 
        name="index" 
        options={{ title: 'Home' }} 
      />
      
      {/* 2. Your Explore Page */}
      <Tabs.Screen 
        name="explore" 
        options={{ title: 'Explore' }} 
      />

      {/* 3. The Single Profile Tab (Hiding the duplicates) */}
      <Tabs.Screen 
        name="consumer-profile" 
        options={{ title: 'Profile' }} 
      />

      {/* 💡 Hiding unwanted tabs completely from showing up on the bottom bar */}
      <Tabs.Screen 
        name="business" 
        options={{ href: null }} // Passing null completely hides this from the bottom bar row
      />
      <Tabs.Screen 
        name="business-profile" 
        options={{ href: null }} 
      />
      <Tabs.Screen 
        name="consumer-home-screen" 
        options={{ href: null }} 
      />
    </Tabs>
  );
}